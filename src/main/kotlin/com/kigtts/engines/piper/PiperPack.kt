// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import org.json.JSONObject
import java.io.File

/** Legacy .kigvpk manifest and standard Piper ONNX/config pairs share this normalized model. */
internal data class PiperPack(val directory: File, val model: File, val config: File, val dictionary: File?,
    val sampleRate: Int, val language: String, val espeakVoice: String, val phonemeType: String,
    val ids: Map<String, List<Int>>, val phoneMap: Map<String, List<String>>, val speakers: Map<String, Int>) {
    companion object {
        fun read(directory: File): PiperPack {
            fun local(path: String): File {
                return File(directory, safePackPath(path)).canonicalFile.also { require(it.toPath().startsWith(directory.canonicalFile.toPath())) }
            }
            val manifest = File(directory, "manifest.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            val files = manifest?.optJSONObject("files")
            val model = files?.optString("model")?.takeIf { it.isNotBlank() }?.let(::local)
                ?: directory.listFiles().orEmpty().filter { it.extension == "onnx" }.singleOrNull() ?: error("语音包需要唯一的 ONNX 模型")
            val config = files?.optString("config")?.takeIf { it.isNotBlank() }?.let(::local)
                ?: File(model.path + ".json")
            require(model.isFile && model.length() > 0 && config.isFile && config.length() <= 4 * 1024 * 1024)
            val json = JSONObject(config.readText())
            val type = json.optString("phoneme_type", if (files?.optString("phonemizer").isNullOrBlank()) "espeak" else "text").trim().lowercase(java.util.Locale.ROOT)
            val dictionary = files?.optString("phonemizer")?.takeIf { it.isNotBlank() }?.let(::local)
            require(dictionary == null || dictionary.isFile) { "缺少发音词典" }
            val rate = manifest?.optInt("sample_rate", 0)?.takeIf { it > 0 }
                ?: json.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: json.optInt("sample_rate", 22050)
            require(rate in 8000..192000)
            val idMap = arrays(json.getJSONObject("phoneme_id_map")) { it.toInt() }
            require(idMap.isNotEmpty() && idMap.values.flatten().all { it in 0..1_000_000 })
            val phoneMap = json.optJSONObject("phoneme_map")?.let { arrays(it) { value -> value } } ?: emptyMap()
            val speakers = json.optJSONObject("speaker_id_map")?.let { map -> map.keys().asSequence().associateWith { map.getInt(it) } }
                ?.takeIf { it.isNotEmpty() } ?: (0 until json.optInt("num_speakers", 1).coerceIn(1, 100)).associate { "声音 ${it + 1}" to it }
            require(speakers.size <= 100 && speakers.values.all { it >= 0 })
            val language = json.optJSONObject("language")?.optString("code").orEmpty()
                .ifBlank { json.optJSONObject("espeak")?.optString("voice").orEmpty() }
                .ifBlank { "und" }.replace('_', '-')
            val voice = json.optJSONObject("espeak")?.optString("voice").orEmpty().ifBlank { language }.ifBlank { "en-us" }
            return PiperPack(directory, model, config, dictionary, rate, language, voice, type, idMap, phoneMap, speakers)
        }
        private fun <T> arrays(json: JSONObject, convert: (String) -> T): Map<String, List<T>> = json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is org.json.JSONArray -> (0 until value.length()).map { convert(value.get(it).toString()) }
                else -> listOf(convert(value.toString()))
            }
        }
    }
}
