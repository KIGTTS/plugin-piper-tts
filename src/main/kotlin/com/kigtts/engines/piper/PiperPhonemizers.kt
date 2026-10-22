// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import java.io.File
import com.lhtstudio.kigtts.app.audio.EspeakNative

private fun buildIds(phones: List<String>, idMap: Map<String, List<Int>>): IntArray {
    val ids = mutableListOf<Int>()
    val bos = idMap["^"] ?: emptyList()
    val eos = idMap["$"] ?: emptyList()
    val pad = idMap["_"] ?: emptyList()
    ids.addAll(bos)
    if (pad.isNotEmpty()) {
        ids.addAll(pad)
    }
    for (phone in phones) {
        val mapped = idMap[phone] ?: continue
        ids.addAll(mapped)
        if (pad.isNotEmpty()) {
            ids.addAll(pad)
        }
    }
    ids.addAll(eos)
    return ids.toIntArray()
}

class PiperPhonemizer(
    dictFile: File?,
    private val idMap: Map<String, List<Int>>,
    private val phoneMap: Map<String, List<String>>
) {
    private val charToPhones: Map<String, List<String>> = dictFile?.let(::loadDict) ?: emptyMap()
    private fun loadDict(file: File): Map<String, List<String>> {
        if (!file.exists()) return emptyMap()
        val map = mutableMapOf<String, List<String>>()
        file.useLines { lines ->
            lines.forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    map[parts[0]] = parts.drop(1)
                }
            }
        }
        return map
    }

    private fun applyPhoneMap(phones: List<String>): List<String> {
        if (phoneMap.isEmpty()) return phones
        val out = mutableListOf<String>()
        for (phone in phones) {
            val mapped = phoneMap[phone]
            if (mapped != null && mapped.isNotEmpty()) {
                out.addAll(mapped)
            } else {
                out.add(phone)
            }
        }
        return out
    }

    fun toIds(text: String): IntArray {
        val phones = mutableListOf<String>()
        text.forEach { ch ->
            val key = ch.toString()
            val entry = charToPhones[key]
            if (entry != null) {
                phones.addAll(entry)
            } else {
                phones.add(key)
            }
        }
        val mappedPhones = applyPhoneMap(phones)
        return buildIds(mappedPhones, idMap)
    }
}

class EspeakPhonemizer(
    assets: File,
    private val dataDir: File,
    private val voice: String,
    private val idMap: Map<String, List<Int>>,
    private val phoneMap: Map<String, List<String>>
) {
    private val japaneseReadingDictionary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JapaneseReadingDictionary(assets)
    }
    private val chinesePinyinDictionary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChinesePinyinDictionary(assets)
    }

    init {
        if (!EspeakNative.ensureInit(dataDir.absolutePath)) {
            throw IllegalStateException("espeak-ng 初始化失败")
        }
    }

    private fun applyPhoneMap(phones: List<String>): List<String> {
        if (phoneMap.isEmpty()) return phones
        val out = mutableListOf<String>()
        for (phone in phones) {
            val mapped = phoneMap[phone]
            if (mapped != null && mapped.isNotEmpty()) {
                out.addAll(mapped)
            } else {
                out.add(phone)
            }
        }
        return out
    }

    private fun legacyToIds(text: String): IntArray {
        val phonemes = EspeakNative.phonemize(text, voice)
        if (phonemes.isBlank()) return IntArray(0)
        val phones = phonemes.codePoints().toArray().map { codePoint ->
            String(Character.toChars(codePoint))
        }
        return buildIds(applyPhoneMap(phones), idMap)
    }

    fun toIds(text: String): IntArray {
        if (!PiperMultilingualCompat.supports(voice)) {
            return legacyToIds(text)
        }
        val requiresForeignRouting = PiperMultilingualCompat.requiresRouting(text)
        val containsChinese = PiperMultilingualCompat.containsHan(text)
        if (!requiresForeignRouting && !containsChinese) return legacyToIds(text)

        val phones = mutableListOf<String>()
        val segments = if (requiresForeignRouting) {
            PiperMultilingualCompat.segment(text)
        } else {
            listOf(PiperTextSegment(PiperTextLanguage.BASE, text))
        }
        for (segment in segments) {
            val chinesePinyin = if (
                segment.language == PiperTextLanguage.BASE &&
                PiperMultilingualCompat.containsHan(segment.text)
            ) {
                chinesePinyinDictionary.toPinyin(segment.text)
            } else {
                null
            }
            val directText = PiperMultilingualCompat.prepareText(segment) {
                japaneseReadingDictionary.toKana(it)
            }
            val directVoice = segment.language.espeakVoice ?: voice
            val foreignPinyin = when (segment.language) {
                PiperTextLanguage.ENGLISH -> {
                    val ipa = EspeakNative.phonemize(directText, directVoice)
                    ForeignChineseApproximation.englishIpaToPinyin(ipa).ifBlank { null }
                }
                PiperTextLanguage.JAPANESE -> {
                    ForeignChineseApproximation.japaneseKanaToPinyin(directText).ifBlank { null }
                }
                PiperTextLanguage.KOREAN -> {
                    ForeignChineseApproximation.koreanHangulToPinyin(directText).ifBlank { null }
                }
                else -> null
            }
            val approximatePinyin = chinesePinyin ?: foreignPinyin
            var adaptationLanguage = if (approximatePinyin != null) {
                PiperTextLanguage.BASE
            } else {
                segment.language
            }
            var phonemes = EspeakNative.phonemize(
                approximatePinyin ?: directText,
                if (approximatePinyin != null) CHINESE_PINYIN_VOICE else directVoice
            )
            if (phonemes.isBlank() && approximatePinyin != null) {
                adaptationLanguage = segment.language
                phonemes = EspeakNative.phonemize(directText, directVoice)
            }
            if (phonemes.isBlank() && segment.language != PiperTextLanguage.BASE) {
                adaptationLanguage = segment.language
                phonemes = EspeakNative.phonemize(directText, voice)
            }
            if (phonemes.isBlank()) continue
            val rawPhones = phonemes.codePoints().toArray().map { cp ->
                String(Character.toChars(cp))
            }
            val modelMappedPhones = applyPhoneMap(rawPhones)
            val adaptedPhones = PiperMultilingualCompat.adaptPhones(
                modelMappedPhones,
                adaptationLanguage,
                idMap
            )
            if (phones.isNotEmpty() && adaptedPhones.isNotEmpty() && phones.last() != " ") {
                phones.add(" ")
            }
            phones.addAll(adaptedPhones)
        }
        return buildIds(phones, idMap)
    }

    private companion object {
        const val CHINESE_PINYIN_VOICE = "cmn-Latn-pinyin"
    }
}
