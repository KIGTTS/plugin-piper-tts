// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun setPiperAvatar(directory: File, source: File) {
    require(source.isFile && source.length() in 1..8L * 1024 * 1024) { "头像文件过大" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) { "头像图片无效或过大" }
    val options = BitmapFactory.Options().apply { inSampleSize = maxOf(bounds.outWidth, bounds.outHeight) / 400 + 1 }
    val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, options))
    val name = "avatar-${UUID.randomUUID()}.png"
    val target = File(directory, name); val temp = File(directory, ".$name.tmp")
    try {
        temp.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val previous = PiperMetadata.read(directory).optString("avatar")
        PiperMetadata.save(directory, PiperMetadata.read(directory).put("avatar", name))
        if (previous.matches(Regex("avatar-[a-f0-9-]+\\.png"))) File(directory, previous).delete()
    } finally { bitmap.recycle(); temp.delete() }
}

internal fun exportPiperPack(directory: File, temporary: File): File {
    val pack = PiperPack.read(directory)
    val output = File(temporary, "piper-export-${UUID.randomUUID()}.kigvpk")
    val manifest = File(directory, "manifest.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    val config = JSONObject(pack.config.readText()).put("phoneme_type", pack.phonemeType)
    val dictionaryPath = pack.dictionary?.relativeTo(directory)?.invariantSeparatorsPath ?: "phonemizer.dict"
    manifest.put("sample_rate", pack.sampleRate).put("name", PiperMetadata.read(directory).getString("name"))
        .put("files", JSONObject().put("model", pack.model.relativeTo(directory).invariantSeparatorsPath)
            .put("config", pack.config.relativeTo(directory).invariantSeparatorsPath).put("phonemizer", dictionaryPath))
    try {
        ZipOutputStream(output.outputStream()).use { zip ->
            fun bytes(path: String, data: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry() }
            val replacements = setOf("manifest.json", "voicepack.json", "voice-meta.json", pack.config.relativeTo(directory).invariantSeparatorsPath)
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(directory).invariantSeparatorsPath
                if (path !in replacements && path !in setOf("voicepack.json.bak", "voicepack.json.tmp", "voice-meta.json.bak") && !file.name.startsWith(".voice")) {
                    zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            bytes("manifest.json", manifest.toString().toByteArray())
            bytes("voicepack.json", PiperMetadata.read(directory).toString().toByteArray())
            bytes(pack.config.relativeTo(directory).invariantSeparatorsPath, config.toString().toByteArray())
            if (pack.dictionary == null) bytes(dictionaryPath, byteArrayOf())
        }
        return output
    } catch (error: Exception) { output.delete(); throw error }
}
