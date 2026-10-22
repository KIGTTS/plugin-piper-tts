// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

internal object ForeignChineseApproximation {
    private val vowels = setOf(
        "a", "e", "i", "o", "u", "y", "ɑ", "ɐ", "ɒ", "æ", "ɔ", "ə", "ɚ", "ɛ",
        "ɜ", "ɝ", "ɞ", "ɪ", "ʊ", "ʌ", "ø", "œ", "ɯ", "ɨ", "ᵻ"
    )
    private val ignoredIpaMarks = setOf("ː", "ˑ", "̞", "̈", "̃", "̩", "̯", "̪", "̧", "ʲ", "˞")

    fun englishIpaToPinyin(ipa: String): String {
        if (ipa.isBlank()) return ""
        val out = StringBuilder(ipa.length * 2)
        val word = StringBuilder()

        fun flushWord() {
            if (word.isEmpty()) return
            appendToken(out, convertEnglishWord(word.toString()))
            word.clear()
        }

        ipa.codePoints().forEach { codePoint ->
            val symbol = String(Character.toChars(codePoint))
            when {
                Character.isLetter(codePoint) || symbol in ignoredIpaMarks || symbol == "ˈ" || symbol == "ˌ" -> {
                    word.append(symbol)
                }
                symbol == "," || symbol == "." || symbol == "!" || symbol == "?" ||
                    symbol == ";" || symbol == ":" -> {
                    flushWord()
                    appendPunctuation(out, symbol[0])
                }
                else -> flushWord()
            }
        }
        flushWord()
        return out.toString().trim()
    }

    fun japaneseKanaToPinyin(text: String): String {
        if (text.isBlank()) return ""
        val normalized = buildString(text.length) {
            text.forEach { char ->
                append(if (char in '\u30A1'..'\u30F6') (char.code - 0x60).toChar() else char)
            }
        }
        val out = StringBuilder(normalized.length * 3)
        var offset = 0
        while (offset < normalized.length) {
            val phrase = japanesePhraseMap.entries.firstOrNull { (surface, _) ->
                normalized.startsWith(surface, offset)
            }
            if (phrase != null) {
                appendToken(out, phrase.value)
                offset += phrase.key.length
                continue
            }

            val pair = normalized.substring(offset, minOf(offset + 2, normalized.length))
            val pairReading = japaneseSyllableMap[pair]
            if (pairReading != null) {
                appendToken(out, pairReading)
                offset += 2
                continue
            }

            val char = normalized[offset]
            when {
                japaneseSyllableMap.containsKey(char.toString()) -> {
                    appendToken(out, requireNotNull(japaneseSyllableMap[char.toString()]))
                }
                char == '、' || char == '，' -> appendPunctuation(out, ',')
                char == '。' -> appendPunctuation(out, '.')
                char == '！' -> appendPunctuation(out, '!')
                char == '？' -> appendPunctuation(out, '?')
                char == 'ー' || char == 'っ' || char == 'ッ' -> Unit
                else -> appendToken(out, char.toString())
            }
            offset++
        }
        return out.toString().trim()
    }

    fun koreanHangulToPinyin(text: String): String {
        if (text.isBlank()) return ""
        val out = StringBuilder(text.length * 4)
        var offset = 0
        while (offset < text.length) {
            val phrase = koreanPhraseMap.entries.firstOrNull { (surface, _) ->
                text.startsWith(surface, offset)
            }
            if (phrase != null) {
                appendToken(out, phrase.value)
                offset += phrase.key.length
                continue
            }

            val char = text[offset]
            when {
                char in '\uAC00'..'\uD7A3' -> appendToken(out, mapHangulSyllable(char))
                char == '、' || char == '，' || char == ',' -> appendPunctuation(out, ',')
                char == '。' || char == '.' -> appendPunctuation(out, '.')
                char == '！' || char == '!' -> appendPunctuation(out, '!')
                char == '？' || char == '?' -> appendPunctuation(out, '?')
                char.isWhitespace() -> Unit
                else -> appendToken(out, char.toString())
            }
            offset++
        }
        return out.toString().trim()
    }

    private fun mapHangulSyllable(char: Char): String {
        val syllable = char.code - 0xAC00
        val initialIndex = syllable / (KOREAN_VOWELS.size * KOREAN_CODA_KIND.size)
        val vowelIndex = (syllable / KOREAN_CODA_KIND.size) % KOREAN_VOWELS.size
        val codaIndex = syllable % KOREAN_CODA_KIND.size
        var initial = KOREAN_INITIALS[initialIndex]
        var final = KOREAN_VOWELS[vowelIndex]

        if (initial in setOf("g", "k", "h") && final.firstOrNull() == 'i') {
            initial = when (initial) {
                "g" -> "j"
                "k" -> "q"
                else -> "x"
            }
        }
        final = appendKoreanCoda(final, KOREAN_CODA_KIND[codaIndex])
        return normalizeKoreanPinyin(initial, final) + "1"
    }

    private fun appendKoreanCoda(final: String, coda: KoreanCodaKind): String = when (coda) {
        KoreanCodaKind.NONE,
        KoreanCodaKind.STOP,
        KoreanCodaKind.LIQUID -> final
        KoreanCodaKind.N -> when (final) {
            "a" -> "an"
            "e", "ei" -> "en"
            "i" -> "in"
            "o" -> "ong"
            "u", "ui", "uo" -> "un"
            "ia", "iao" -> "ian"
            "ie", "iu" -> "in"
            "ua", "uai" -> "uan"
            else -> final
        }
        KoreanCodaKind.NG -> when (final) {
            "a" -> "ang"
            "e", "ei" -> "eng"
            "i" -> "ing"
            "o", "u", "ui", "uo" -> "ong"
            "ia", "iao" -> "iang"
            "ie", "iu" -> "iong"
            "ua", "uai" -> "uang"
            else -> final
        }
    }

    private fun normalizeKoreanPinyin(initial: String, final: String): String {
        if (initial.isNotEmpty()) return initial + final
        return when (final) {
            "i", "in", "ing" -> "y$final"
            "ia" -> "ya"
            "ian" -> "yan"
            "iang" -> "yang"
            "iao" -> "yao"
            "ie" -> "ye"
            "iong" -> "yong"
            "iu" -> "you"
            "u", "un" -> "w$final"
            "ua" -> "wa"
            "uan" -> "wan"
            "uang" -> "wang"
            "ui" -> "wei"
            "uai" -> "wai"
            "uo" -> "wo"
            else -> final
        }
    }

    private fun convertEnglishWord(word: String): String {
        val phones = mutableListOf<IpaPhone>()
        var pendingStress = 0
        word.codePoints().forEach { codePoint ->
            val symbol = String(Character.toChars(codePoint))
            when {
                symbol == "ˈ" -> pendingStress = 1
                symbol == "ˌ" -> pendingStress = 2
                symbol in ignoredIpaMarks || isCombiningMark(codePoint) -> Unit
                else -> {
                    val stressed = if (symbol in vowels) pendingStress else 0
                    phones += IpaPhone(symbol, stressed)
                    if (symbol in vowels) pendingStress = 0
                }
            }
        }
        if (phones.none { it.symbol in vowels }) {
            return phones.mapNotNull { echoSyllable(it.symbol) }.joinToString(" ")
        }

        val nuclei = mutableListOf<IntRange>()
        var index = 0
        while (index < phones.size) {
            if (phones[index].symbol !in vowels) {
                index++
                continue
            }
            val start = index
            while (index + 1 < phones.size && phones[index + 1].symbol in vowels) index++
            nuclei += start..index
            index++
        }

        val syllables = nuclei.map { nucleus ->
            ApproximateSyllable(
                nucleus = phones.slice(nucleus).map(IpaPhone::symbol),
                stress = phones.slice(nucleus).maxOf(IpaPhone::stress)
            )
        }
        val prefix = phones.subList(0, nuclei.first().first).map(IpaPhone::symbol)
        syllables.first().onset += prefix
        for (nucleusIndex in 1 until nuclei.size) {
            val previous = nuclei[nucleusIndex - 1]
            val current = nuclei[nucleusIndex]
            val cluster = phones.subList(previous.last + 1, current.first).map(IpaPhone::symbol)
            if (cluster.isNotEmpty()) {
                syllables[nucleusIndex - 1].coda += cluster.dropLast(1)
                syllables[nucleusIndex].onset += cluster.last()
            }
        }
        syllables.last().coda += phones.subList(nuclei.last().last + 1, phones.size).map(IpaPhone::symbol)
        return syllables.flatMap(::mapEnglishSyllable).joinToString(" ")
    }

    private fun mapEnglishSyllable(syllable: ApproximateSyllable): List<String> {
        val initial = mapInitial(syllable.onset)
        var final = mapNucleus(syllable.nucleus)
        val remainingCoda = syllable.coda.toMutableList()
        if (remainingCoda.firstOrNull() == "n" || remainingCoda.firstOrNull() == "ŋ") {
            final = appendNasal(final, remainingCoda.removeAt(0))
        }
        val tone = if (syllable.stress == 1) 1 else if (syllable.stress == 2) 2 else 5
        val primary = normalizePinyin(initial, final) + tone
        val echoes = remainingCoda.mapNotNull(::echoSyllable).fold(mutableListOf<String>()) { out, value ->
            if (out.lastOrNull() != value) out += value
            out
        }
        return listOf(primary) + echoes
    }

    private fun mapInitial(onset: List<String>): String {
        val joined = onset.joinToString("")
        return when {
            joined.endsWith("tʃ") -> "q"
            joined.endsWith("dʒ") -> "j"
            joined.endsWith("ʃ") -> "x"
            joined.endsWith("ʒ") -> "r"
            joined.endsWith("θ") -> "s"
            joined.endsWith("ð") -> "d"
            else -> when (onset.lastOrNull()) {
                "p" -> "p"
                "b" -> "b"
                "m" -> "m"
                "f" -> "f"
                "v", "w" -> "w"
                "t" -> "t"
                "d" -> "d"
                "n", "ŋ" -> "n"
                "l" -> "l"
                "k" -> "k"
                "g", "ɡ" -> "g"
                "h", "x" -> "h"
                "s" -> "s"
                "z" -> "z"
                "ɹ", "r" -> "r"
                "j" -> "y"
                else -> ""
            }
        }
    }

    private fun mapNucleus(nucleus: List<String>): String {
        val joined = nucleus.joinToString("")
        return when {
            "aɪ" in joined || "ɑɪ" in joined -> "ai"
            "aʊ" in joined || "ɑʊ" in joined -> "ao"
            "eɪ" in joined -> "ei"
            "oʊ" in joined || "əʊ" in joined -> "ou"
            "ɔɪ" in joined -> "ui"
            joined.any { it == 'ɚ' || it == 'ɝ' } -> "er"
            joined.any { it == 'i' || it == 'ɪ' || it == 'y' || it == 'ᵻ' } -> "i"
            joined.any { it == 'u' || it == 'ʊ' || it == 'ɯ' } -> "u"
            joined.any { it == 'æ' } -> "ai"
            joined.any { it == 'e' || it == 'ɛ' } -> "ei"
            joined.any { it == 'o' || it == 'ɔ' || it == 'ɒ' || it == 'ɜ' } -> "o"
            joined.any { it == 'a' || it == 'ɑ' || it == 'ɐ' } -> "a"
            else -> "e"
        }
    }

    private fun appendNasal(final: String, nasal: String): String = when (nasal) {
        "ŋ" -> when (final) {
            "a" -> "ang"
            "e", "ei" -> "eng"
            "i" -> "ing"
            "o", "ou" -> "ong"
            "u" -> "ong"
            else -> final
        }
        else -> when (final) {
            "a" -> "an"
            "e", "ei" -> "en"
            "i" -> "in"
            "u" -> "un"
            else -> final
        }
    }

    private fun normalizePinyin(initial: String, final: String): String {
        if (initial.isNotEmpty()) return initial + final
        return when (final) {
            "i" -> "yi"
            "in" -> "yin"
            "ing" -> "ying"
            "u" -> "wu"
            "un" -> "wen"
            "ong" -> "weng"
            else -> final
        }
    }

    private fun echoSyllable(phone: String): String? = when (phone) {
        "r", "ɹ", "l" -> "er5"
        "d" -> "de5"
        "t" -> "te5"
        "k" -> "ke5"
        "g", "ɡ" -> "ge5"
        "s", "ʃ", "θ" -> "si5"
        "z", "ʒ", "ð" -> "zi5"
        "m" -> "mu5"
        "p" -> "pu5"
        "b" -> "bu5"
        "f", "v" -> "fu5"
        else -> null
    }

    private fun appendToken(out: StringBuilder, token: String) {
        if (token.isBlank()) return
        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
        out.append(token.trim()).append(' ')
    }

    private fun appendPunctuation(out: StringBuilder, punctuation: Char) {
        if (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
        out.append(punctuation).append(' ')
    }

    private fun isCombiningMark(codePoint: Int): Boolean = Character.getType(codePoint) in setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt()
    )

    private data class IpaPhone(val symbol: String, val stress: Int)

    private data class ApproximateSyllable(
        val onset: MutableList<String> = mutableListOf(),
        val nucleus: List<String>,
        val coda: MutableList<String> = mutableListOf(),
        val stress: Int
    )

    private enum class KoreanCodaKind {
        NONE,
        N,
        NG,
        LIQUID,
        STOP
    }

    private val japanesePhraseMap = linkedMapOf(
        "にほんご" to "ni1 hong1 guo1",
        "ありがとうございます" to "a1 li1 ga1 tuo1 wu1 go1 zai1 yi1 ma1 si1",
        "こんにちは" to "kong1 ni1 qi1 wa1",
        "こんばんは" to "kong1 bang1 wa1"
    )

    private val japaneseSyllableMap = mapOf(
        "きゃ" to "jia1", "きゅ" to "jiu1", "きょ" to "jiao1",
        "しゃ" to "xia1", "しゅ" to "xiu1", "しょ" to "xiao1",
        "ちゃ" to "qia1", "ちゅ" to "qiu1", "ちょ" to "qiao1",
        "にゃ" to "nia1", "にゅ" to "niu1", "にょ" to "niao1",
        "ひゃ" to "xia1", "ひゅ" to "xiu1", "ひょ" to "xiao1",
        "みゃ" to "mia1", "みゅ" to "miu1", "みょ" to "miao1",
        "りゃ" to "lia1", "りゅ" to "liu1", "りょ" to "liao1",
        "ぎゃ" to "jia1", "ぎゅ" to "jiu1", "ぎょ" to "jiao1",
        "じゃ" to "jia1", "じゅ" to "ju1", "じょ" to "jiao1",
        "びゃ" to "bia1", "びゅ" to "biu1", "びょ" to "biao1",
        "ぴゃ" to "pia1", "ぴゅ" to "piu1", "ぴょ" to "piao1",
        "あ" to "a1", "い" to "yi1", "う" to "wu1", "え" to "ei1", "お" to "ou1",
        "か" to "ka1", "き" to "qi1", "く" to "ku1", "け" to "ke1", "こ" to "kou1",
        "さ" to "sa1", "し" to "xi1", "す" to "su1", "せ" to "sei1", "そ" to "sou1",
        "た" to "ta1", "ち" to "qi1", "つ" to "ci1", "て" to "tei1", "と" to "tuo1",
        "な" to "na1", "に" to "ni1", "ぬ" to "nu1", "ね" to "nei1", "の" to "nou1",
        "は" to "ha1", "ひ" to "xi1", "ふ" to "fu1", "へ" to "hei1", "ほ" to "hou1",
        "ま" to "ma1", "み" to "mi1", "む" to "mu1", "め" to "mei1", "も" to "mo1",
        "や" to "ya1", "ゆ" to "yu1", "よ" to "you1",
        "ら" to "la1", "り" to "li1", "る" to "lu1", "れ" to "lei1", "ろ" to "lou1",
        "わ" to "wa1", "を" to "wo1", "ん" to "en1",
        "が" to "ga1", "ぎ" to "ji1", "ぐ" to "gu1", "げ" to "gei1", "ご" to "guo1",
        "ざ" to "za1", "じ" to "ji1", "ず" to "zu1", "ぜ" to "zei1", "ぞ" to "zou1",
        "だ" to "da1", "ぢ" to "ji1", "づ" to "zu1", "で" to "dei1", "ど" to "duo1",
        "ば" to "ba1", "び" to "bi1", "ぶ" to "bu1", "べ" to "bei1", "ぼ" to "bo1",
        "ぱ" to "pa1", "ぴ" to "pi1", "ぷ" to "pu1", "ぺ" to "pei1", "ぽ" to "po1"
    )

    private val koreanPhraseMap = linkedMapOf(
        "안녕하세요" to "a1 ni1 ha1 sai1 you1",
        "안녕히 가세요" to "an1 ni1 xi1 ga1 sai1 you1",
        "감사합니다" to "gan1 sa1 ha1 mi1 da1",
        "감사해요" to "gan1 sa1 hei1 you1",
        "죄송합니다" to "zui1 song1 ha1 mi1 da1",
        "미안합니다" to "mi1 an1 ha1 mi1 da1",
        "괜찮아요" to "kai1 can1 na1 you1",
        "사랑해요" to "sa1 lang1 hei1 you1",
        "한국어" to "han2 gu1 ge1",
        "입니다" to "yi1 mi1 da1",
        "주세요" to "ju1 sai1 you1",
        "아니요" to "a1 ni1 you1",
        "네" to "nei1"
    )

    private val KOREAN_INITIALS = arrayOf(
        "g", "k", "n", "d", "t", "l", "m", "b", "p", "s",
        "s", "", "j", "j", "q", "k", "t", "p", "h"
    )

    private val KOREAN_VOWELS = arrayOf(
        "a", "ai", "ia", "ie", "e", "ei", "ie", "ie", "o", "ua", "uai",
        "ui", "iao", "u", "uo", "ui", "ui", "iu", "e", "i", "i"
    )

    private val KOREAN_CODA_KIND = arrayOf(
        KoreanCodaKind.NONE,
        KoreanCodaKind.STOP, KoreanCodaKind.STOP, KoreanCodaKind.STOP,
        KoreanCodaKind.N, KoreanCodaKind.N, KoreanCodaKind.N,
        KoreanCodaKind.STOP,
        KoreanCodaKind.LIQUID, KoreanCodaKind.LIQUID, KoreanCodaKind.LIQUID,
        KoreanCodaKind.LIQUID, KoreanCodaKind.LIQUID, KoreanCodaKind.LIQUID,
        KoreanCodaKind.LIQUID, KoreanCodaKind.LIQUID,
        KoreanCodaKind.N,
        KoreanCodaKind.STOP, KoreanCodaKind.STOP, KoreanCodaKind.STOP,
        KoreanCodaKind.STOP,
        KoreanCodaKind.NG,
        KoreanCodaKind.STOP, KoreanCodaKind.STOP, KoreanCodaKind.STOP,
        KoreanCodaKind.STOP, KoreanCodaKind.STOP, KoreanCodaKind.STOP
    )
}
