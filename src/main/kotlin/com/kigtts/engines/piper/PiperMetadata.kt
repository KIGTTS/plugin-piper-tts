// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import com.kigtts.plugin.api.VoicePackMetadata
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object PiperMetadata {
    fun read(directory: File): JSONObject {
        fun json(name: String) = runCatching { JSONObject(File(directory, name).readText().removePrefix("\uFEFF")) }.getOrNull()
        val old = json("voicepack.json") ?: json("voicepack.json.bak") ?: json("meta.json") ?: JSONObject()
        val api3 = json("voice-meta.json")
        if (old.optInt("_kigttsMetadataVersion", 0) < 4) api3?.keys()?.forEach { old.put(it, api3.get(it)) }
        val manifest = json("manifest.json")
        val fallback = listOf("name", "title", "voice").mapNotNull { manifest?.optString(it)?.takeIf(String::isNotBlank) }.firstOrNull() ?: directory.name
        old.put("name", old.optString("name").trim().ifBlank { fallback })
        old.put("remark", old.optString("remark"))
        old.put("avatar", runCatching { safePackPath(old.optString("avatar", "avatar.png")) }.getOrDefault("avatar.png"))
        old.put("pinned", old.optBoolean("pinned", false))
        old.put("order", old.optLong("order", directory.lastModified()).coerceAtLeast(0))
        return old
    }
    fun data(json: JSONObject) = VoicePackMetadata(json.getString("name"), json.optString("remark"), json.optBoolean("pinned"), json.optLong("order"))
    fun save(directory: File, json: JSONObject) {
        json.put("_kigttsMetadataVersion", 4)
        for (name in listOf("voicepack.json", "voice-meta.json")) {
            val target = File(directory, name); val temporary = File(directory, ".$name-${UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(json.toString())
                try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { temporary.delete() }
        }
    }
    fun update(directory: File, metadata: VoicePackMetadata) {
        require(metadata.name.trim().length in 1..80 && metadata.remark.length <= 2000 && metadata.order >= 0)
        save(directory, read(directory).put("name", metadata.name.trim()).put("remark", metadata.remark).put("pinned", metadata.pinned).put("order", metadata.order))
    }
}

internal fun safePackPath(path: String): String {
    val normalized = path.trim().replace('\\', '/')
    require(!normalized.startsWith("//") && !normalized.contains(':') && !normalized.contains('\u0000'))
    val parts = normalized.removePrefix("/").split('/').filter { it != "." }
    require(parts.isNotEmpty() && parts.none { it == ".." || it.isEmpty() })
    return parts.joinToString("/")
}
