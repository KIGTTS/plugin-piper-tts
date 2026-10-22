// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import com.kigtts.plugin.api.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

internal class PiperVoices(private val storage: PluginStorage) {
    private val root = storage.directory("models/voices")
    private val settings = storage.file("config/selection.json").also { storage.configuration }
    var selected: String? = runCatching { JSONObject(settings.readText()).optString("voice").takeIf(String::isNotBlank) }.getOrNull(); private set
    fun packs(): List<VoicePackInfo> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("voice-") }.mapNotNull { dir ->
        runCatching {
            val pack = PiperPack.read(dir); val meta = PiperMetadata.read(dir)
            val image = File(dir, meta.getString("avatar"))
            VoicePackInfo(dir.name, PiperMetadata.data(meta), pack.speakers.values.map { "${dir.name}:$it" },
                if (image.isFile) "models/voices/${dir.name}/${meta.getString("avatar")}" else null,
                editable = true, exportable = true, deletable = true, parameters = PiperAudioSettings.parameters(pack))
        }.getOrNull()
    }.sortedWith(compareByDescending<VoicePackInfo> { it.metadata.pinned }.thenBy { it.metadata.order }.thenBy { it.metadata.name })
    fun list(): List<VoiceInfo> = packs().flatMap { info ->
        val pack = PiperPack.read(directory(info.id))
        pack.speakers.map { (speaker, id) -> VoiceInfo("${info.id}:$id", if (pack.speakers.size == 1) info.metadata.name else "${info.metadata.name} · $speaker",
            pack.language, info.metadata.remark, true, info.metadata.name, info.id) }
    }
    fun directory(id: String): File {
        require(id.matches(Regex("voice-[a-f0-9-]+"))) { "无效语音包 ID" }
        return File(root, id).also { require(it.isDirectory) { "语音包不存在" } }
    }
    fun choose(id: String) { require(list().any { it.id == id }) { "声音不存在" }; selected = id; save() }
    fun resolve(id: String?): Pair<PiperPack, Int> {
        val voice = id ?: selected ?: list().firstOrNull()?.id ?: throw EngineException(EngineError.NOT_CONFIGURED, "Piper 尚未导入语音包")
        require(list().any { it.id == voice }) { "声音不存在" }
        return PiperPack.read(directory(voice.substringBefore(':'))) to voice.substringAfter(':').toInt()
    }
    fun import(archive: File, replacing: String? = null): VoiceInfo {
        require(archive.isFile && archive.length() in 1..2L * 1024 * 1024 * 1024)
        val previous = replacing?.let(::directory)
        val staging = File(storage.temporary, "voice-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size in 1..4096 && entries.sumOf { it.size } in 1..2L * 1024 * 1024 * 1024)
                val paths = entries.map { safePackPath(it.name.removeSuffix("/")) }
                require(paths.distinct().size == paths.size)
                entries.zip(paths).forEach { (entry, path) ->
                    require(entry.size in 0..2L * 1024 * 1024 * 1024)
                    val target = File(staging, path)
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { source -> target.outputStream().use { require(source.copyTo(it) == entry.size) } }
                    }
                }
            }
            val directory = staging.walkTopDown().firstOrNull { it.isDirectory && (File(it, "manifest.json").isFile || it.listFiles().orEmpty().any { f -> f.extension.equals("onnx", true) }) }
                ?: error("未找到 Piper 语音包")
            PiperPack.read(directory)
            val meta = previous?.let(PiperMetadata::read) ?: PiperMetadata.read(directory)
            previous?.let { old ->
                val avatar = File(old, meta.getString("avatar"))
                if (avatar.isFile) File(directory, meta.getString("avatar")).also { it.parentFile!!.mkdirs(); avatar.copyTo(it, overwrite = true) }
            }
            if (meta.optString("name") == directory.name) meta.put("name", "Piper 语音 ${packs().size + 1}")
            PiperMetadata.save(directory, meta)
            val id = replacing ?: "voice-${UUID.randomUUID()}"
            val destination = File(root, id)
            val backup = File(storage.temporary, "voice-backup-${UUID.randomUUID()}")
            if (previous != null) require(previous.renameTo(backup)) { "旧语音包无法暂存" }
            try { require(directory.renameTo(destination)) { "语音包安装失败" } }
            catch (error: Exception) { if (previous != null) require(backup.renameTo(destination)) { "语音包恢复失败" }; throw error }
            backup.deleteRecursively()
            val result = list().first { it.packId == id }
            if (selected == null || selected?.startsWith("$id:") == true && list().none { it.id == selected }) choose(result.id)
            return result
        } finally { staging.deleteRecursively() }
    }
    fun delete(id: String) {
        val packId = id.substringBefore(':'); val dir = directory(packId)
        require(dir.deleteRecursively()) { "语音包清理失败" }
        if (selected?.startsWith("$packId:") == true) { selected = list().firstOrNull()?.id; save() }
    }
    fun update(id: String, metadata: VoicePackMetadata) = PiperMetadata.update(directory(id.substringBefore(':')), metadata)
    fun rename(id: String, name: String) {
        val info = packs().first { it.id == id.substringBefore(':') }; update(info.id, info.metadata.copy(name = name))
    }
    fun reorder(order: List<String>) {
        val current = packs(); ItemOrder.validate(order, current.map { it.id })
        order.forEachIndexed { i, id -> update(id, current.first { it.id == id }.metadata.copy(order = i.toLong())) }
    }
    fun avatar(id: String, source: File) = setPiperAvatar(directory(id), source)
    fun parameters(id: String, value: String) = PiperAudioSettings.update(PiperPack.read(directory(id)), value)
    fun export(id: String): File = exportPiperPack(directory(id), storage.temporary)
    private fun save() { settings.writeText(JSONObject().put("voice", selected.orEmpty()).toString()) }
}
