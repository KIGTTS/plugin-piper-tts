// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import com.kigtts.plugin.api.VoiceParameter
import org.json.JSONObject

/** Stored alongside pack metadata, so updates, exports and different speakers share one preset. */
internal object PiperAudioSettings {
    fun parameters(pack: PiperPack): List<VoiceParameter> {
        val defaults = JSONObject(pack.config.readText()).optJSONObject("inference") ?: JSONObject()
        val saved = PiperMetadata.read(pack.directory).optJSONObject("audioSettings") ?: JSONObject()
        fun parameter(id: String, label: String, fallback: Double, max: Float, suffix: String = ""): VoiceParameter {
            val initial = defaults.optDouble(id, fallback).toFloat().takeIf { it.isFinite() && it in 0f..max } ?: fallback.toFloat()
            val value = saved.optDouble(id, initial.toDouble()).toFloat().takeIf { it.isFinite() && it in 0f..max } ?: initial
            return VoiceParameter(id, label, value, 0f, max, initial, suffix = suffix)
        }
        return listOf(parameter("noise_scale", "音色变化", .667, 2f),
            parameter("noise_w", "节奏变化", .8, 2f), parameter("volume", "音量", 1.0, 2f, "×"))
    }
    fun update(pack: PiperPack, encoded: String) {
        val values = JSONObject(encoded)
        val known = parameters(pack).associateBy { it.id }
        require(values.length() in 1..known.size)
        val meta = PiperMetadata.read(pack.directory)
        val saved = meta.optJSONObject("audioSettings") ?: JSONObject()
        // Validate the whole patch before writing anything.
        val checked = values.keys().asSequence().associateWith { id ->
            val spec = requireNotNull(known[id]) { "不支持的语音参数" }
            val value = values.getDouble(id).toFloat()
            require(value.isFinite() && value in spec.min..spec.max) { "语音参数超出范围" }
            value
        }
        checked.forEach { (id, value) -> saved.put(id, value) }
        PiperMetadata.save(pack.directory, meta.put("audioSettings", saved))
    }
    fun scales(pack: PiperPack, rate: Float): FloatArray {
        val values = parameters(pack).associate { it.id to it.value }
        val inference = JSONObject(pack.config.readText()).optJSONObject("inference")
        val length = inference?.optDouble("length_scale", 1.0)?.toFloat()?.takeIf { it.isFinite() && it in .1f..10f } ?: 1f
        return floatArrayOf(values.getValue("noise_scale"), length / rate, values.getValue("noise_w"))
    }
}
