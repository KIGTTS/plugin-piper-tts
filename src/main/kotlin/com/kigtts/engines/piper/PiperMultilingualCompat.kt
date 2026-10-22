// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

internal enum class PiperTextLanguage(val espeakVoice: String?) {
    BASE(null),
    ENGLISH("en-us"),
    JAPANESE("ja"),
    KOREAN("ko")
}

internal data class PiperTextSegment(
    val language: PiperTextLanguage,
    val text: String
)

internal object PiperMultilingualCompat {
    private val punctuation = setOf(" ", "!", "'", ",", "-", ".", ":", ";", "?")
    private val ignoredForeignPhones = setOf(
        "ˌ", "ː", "ˑ", "˞", "ʲ", "̧", "̃", "̪", "̯", "̩", "ˤ", "̞", "̈"
    )
    private val commonForeignPhoneMap = mapOf(
        "b" to listOf("p"),
        "c" to listOf("s"),
        "d" to listOf("t"),
        "g" to listOf("k"),
        "ɡ" to listOf("k"),
        "q" to listOf("k"),
        "v" to listOf("f"),
        "æ" to listOf("a"),
        "ɐ" to listOf("a"),
        "ɒ" to listOf("ɑ"),
        "ɔ" to listOf("o"),
        "ɘ" to listOf("ə"),
        "ɚ" to listOf("ɜ", "r"),
        "ɞ" to listOf("o"),
        "ɪ" to listOf("i"),
        "ɫ" to listOf("l"),
        "ɯ" to listOf("u"),
        "ɴ" to listOf("n"),
        "ɵ" to listOf("o"),
        "ɹ" to listOf("r"),
        "ɾ" to listOf("l"),
        "ʀ" to listOf("r"),
        "ʁ" to listOf("r"),
        "ʃ" to listOf("ʂ"),
        "ʊ" to listOf("u"),
        "ʌ" to listOf("ɜ"),
        "ʑ" to listOf("ɕ"),
        "ʒ" to listOf("ʐ"),
        "β" to listOf("p"),
        "θ" to listOf("s"),
        "ð" to listOf("z"),
        "ᵻ" to listOf("i"),
        "ε" to listOf("ɛ")
    )
    private val languagePhoneMap = mapOf(
        PiperTextLanguage.ENGLISH to mapOf(
            "ə" to listOf("ɜ"),
            "ɜ" to listOf("ɜ"),
            "ɝ" to listOf("ɜ", "r")
        ),
        PiperTextLanguage.JAPANESE to mapOf(
            "ç" to listOf("ɕ"),
            "ɸ" to listOf("f"),
            "ʃ" to listOf("ɕ"),
            "ʑ" to listOf("ɕ")
        ),
        PiperTextLanguage.KOREAN to mapOf(
            "ɐ" to listOf("a"),
            "ʌ" to listOf("ɜ"),
            "ɯ" to listOf("u"),
            "ɾ" to listOf("l")
        )
    )

    fun supports(baseVoice: String): Boolean {
        val normalized = baseVoice.trim().lowercase().replace('_', '-')
        return normalized == "cmn" || normalized == "zh" || normalized.startsWith("zh-")
    }

    fun requiresRouting(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        isLatin(codePoint) || isKana(codePoint) || isHangul(codePoint)
    }

    fun containsHan(text: String): Boolean = text.codePoints().anyMatch(::isHan)

    fun segment(text: String): List<PiperTextSegment> {
        if (text.isEmpty()) return emptyList()
        val codePoints = text.codePoints().toArray()
        val languages = Array<PiperTextLanguage?>(codePoints.size) { index ->
            classify(codePoints[index])
        }
        for (index in codePoints.indices) {
            if (languages[index] == null && Character.isDigit(codePoints[index])) {
                languages[index] = contextualLanguage(codePoints, index)
            } else if (languages[index] == PiperTextLanguage.BASE && isHan(codePoints[index])) {
                languages[index] = contextualCjkLanguage(codePoints, index)
            }
        }

        val segments = mutableListOf<PiperTextSegment>()
        val currentText = StringBuilder()
        var currentLanguage = PiperTextLanguage.BASE

        fun flush() {
            if (currentText.isEmpty()) return
            segments += PiperTextSegment(currentLanguage, currentText.toString())
            currentText.clear()
        }

        codePoints.forEachIndexed { index, codePoint ->
            val language = languages[index]
            if (language != null && language != currentLanguage) {
                flush()
                currentLanguage = language
            }
            currentText.appendCodePoint(codePoint)
        }
        flush()
        return segments
    }

    fun prepareText(
        segment: PiperTextSegment,
        japaneseReading: (String) -> String
    ): String {
        val containsHan = containsHan(segment.text)
        if (segment.language != PiperTextLanguage.JAPANESE || !containsHan) {
            return segment.text
        }
        return japaneseReading(segment.text)
    }

    fun adaptPhones(
        phones: List<String>,
        language: PiperTextLanguage,
        idMap: Map<String, List<Int>>
    ): List<String> {
        if (language == PiperTextLanguage.BASE) return phones
        val out = mutableListOf<String>()
        for (phone in phones) {
            if (idMap.containsKey(phone)) {
                out += phone
                continue
            }
            if (phone in ignoredForeignPhones) continue
            val mapped = languagePhoneMap[language]?.get(phone)
                ?: commonForeignPhoneMap[phone]
                ?: listOf(phone)
            appendSupported(out, mapped, idMap)
        }
        return out
    }

    private fun appendSupported(
        out: MutableList<String>,
        candidates: List<String>,
        idMap: Map<String, List<Int>>
    ) {
        for (candidate in candidates) {
            when {
                idMap.containsKey(candidate) -> out += candidate
                candidate in punctuation -> Unit
                else -> fallbackPhone(candidate, idMap)?.let(out::add)
            }
        }
    }

    private fun fallbackPhone(phone: String, idMap: Map<String, List<Int>>): String? {
        val candidates = when {
            phone.firstOrNull()?.isLetter() == true -> listOf("ə", "ɜ", "a")
            phone.firstOrNull()?.isDigit() == true -> listOf(phone, " ")
            else -> emptyList()
        }
        return candidates.firstOrNull(idMap::containsKey)
    }

    private fun classify(codePoint: Int): PiperTextLanguage? = when {
        isKana(codePoint) -> PiperTextLanguage.JAPANESE
        isHangul(codePoint) -> PiperTextLanguage.KOREAN
        isLatin(codePoint) -> PiperTextLanguage.ENGLISH
        isHan(codePoint) -> PiperTextLanguage.BASE
        else -> null
    }

    private fun contextualLanguage(codePoints: IntArray, index: Int): PiperTextLanguage {
        val bounds = wordBounds(codePoints, index)
        val token = codePoints.sliceArray(bounds)
        return when {
            token.any(::isKana) -> PiperTextLanguage.JAPANESE
            token.any(::isHangul) -> PiperTextLanguage.KOREAN
            token.any(::isLatin) -> PiperTextLanguage.ENGLISH
            else -> PiperTextLanguage.BASE
        }
    }

    private fun contextualCjkLanguage(codePoints: IntArray, index: Int): PiperTextLanguage {
        val bounds = wordBounds(codePoints, index, cjkOnly = true)
        val token = codePoints.sliceArray(bounds)
        return when {
            token.any(::isKana) -> PiperTextLanguage.JAPANESE
            token.any(::isHangul) -> PiperTextLanguage.KOREAN
            else -> PiperTextLanguage.BASE
        }
    }

    private fun wordBounds(codePoints: IntArray, index: Int, cjkOnly: Boolean = false): IntRange {
        fun isWord(codePoint: Int): Boolean = if (cjkOnly) {
            isHan(codePoint) || isKana(codePoint) || isHangul(codePoint)
        } else {
            Character.isLetterOrDigit(codePoint)
        }

        var start = index
        var end = index
        while (start > 0 && isWord(codePoints[start - 1])) start--
        while (end < codePoints.lastIndex && isWord(codePoints[end + 1])) end++
        return start..end
    }

    private fun isLatin(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return Character.isLetter(codePoint) && block in setOf(
            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A,
            Character.UnicodeBlock.LATIN_EXTENDED_B,
            Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
        )
    }

    private fun isKana(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            codePoint == 0x30FC
    }

    private fun isHangul(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
            block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A ||
            block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
    }

    private fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) ==
        Character.UnicodeScript.HAN
}
