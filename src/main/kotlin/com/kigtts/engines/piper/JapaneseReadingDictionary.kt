// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import java.io.File
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.nio.charset.StandardCharsets

internal class JapaneseReadingDictionary(assets: File) {
    private val assetRoot = assets
    private val index by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            File(assetRoot, ASSET_PATH).inputStream().use { source ->
                XZInputStream(source).use { compressed ->
                    JapaneseReadingIndex.fromBytes(compressed.readBytes())
                }
            }
        }.onFailure {
            Log.e("PiperReading", "Japanese reading dictionary load failed", it)
        }.getOrNull()
    }

    fun toKana(text: String): String = index?.toKana(text) ?: text

    private companion object {
        const val ASSET_PATH = "japanese_reading/ipadic_readings.tsv.xz"
    }
}

internal class JapaneseReadingIndex private constructor(
    private val data: ByteArray,
    private val lineOffsets: IntArray,
    private val maxWordLength: Int
) {
    fun toKana(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (!isHan(codePoint)) {
                out.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
                continue
            }

            val candidateEnds = candidateEnds(text, offset)
            var matchedEnd = -1
            var matchedReading: String? = null
            for (end in candidateEnds.asReversed()) {
                val reading = findReading(text.substring(offset, end)) ?: continue
                matchedEnd = end
                matchedReading = reading
                break
            }
            if (matchedReading != null) {
                out.append(matchedReading)
                offset = matchedEnd
            } else {
                out.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }
        return out.toString()
    }

    private fun candidateEnds(text: String, start: Int): List<Int> {
        val ends = ArrayList<Int>(maxWordLength)
        var offset = start
        var count = 0
        while (offset < text.length && count < maxWordLength) {
            val codePoint = text.codePointAt(offset)
            if (!Character.isLetterOrDigit(codePoint) && !isJapaneseIterationMark(codePoint)) break
            offset += Character.charCount(codePoint)
            ends += offset
            count++
        }
        return ends
    }

    private fun findReading(surface: String): String? {
        val key = surface.toByteArray(StandardCharsets.UTF_8)
        var low = 0
        var high = lineOffsets.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val comparison = compareSurface(lineOffsets[mid], key)
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid - 1
                else -> return readValue(lineOffsets[mid])
            }
        }
        return null
    }

    private fun compareSurface(lineOffset: Int, key: ByteArray): Int {
        var dataIndex = lineOffset
        var keyIndex = 0
        while (dataIndex < data.size && data[dataIndex] != TAB && data[dataIndex] != NEWLINE) {
            if (keyIndex >= key.size) return 1
            val dataByte = data[dataIndex].toInt() and 0xFF
            val keyByte = key[keyIndex].toInt() and 0xFF
            if (dataByte != keyByte) return dataByte - keyByte
            dataIndex++
            keyIndex++
        }
        return if (keyIndex == key.size) 0 else -1
    }

    private fun readValue(lineOffset: Int): String {
        var start = lineOffset
        while (start < data.size && data[start] != TAB) start++
        start++
        var end = start
        while (end < data.size && data[end] != NEWLINE) end++
        return String(data, start, end - start, StandardCharsets.UTF_8)
    }

    private fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) ==
        Character.UnicodeScript.HAN

    private fun isJapaneseIterationMark(codePoint: Int): Boolean = when (codePoint) {
        0x3005, 0x309D, 0x309E, 0x30FD, 0x30FE -> true
        else -> false
    }

    internal companion object {
        private const val MAGIC = "#KIGTTS-JA-READING-1"
        private const val TAB: Byte = 0x09
        private const val NEWLINE: Byte = 0x0A

        fun fromBytes(data: ByteArray): JapaneseReadingIndex {
            val headerEnd = data.indexOf(NEWLINE)
            require(headerEnd > 0) { "日语读音词典缺少文件头" }
            val header = String(data, 0, headerEnd, StandardCharsets.UTF_8).split('\t')
            require(header.size == 3 && header[0] == MAGIC) { "日语读音词典格式不受支持" }
            val entryCount = header[1].toInt()
            val maxWordLength = header[2].toInt()
            val offsets = IntArray(entryCount)
            var line = 0
            var offset = headerEnd + 1
            while (offset < data.size && line < offsets.size) {
                offsets[line++] = offset
                val newline = data.indexOf(NEWLINE, offset)
                offset = if (newline >= 0) newline + 1 else data.size
            }
            require(line == entryCount) { "日语读音词典条目数量不匹配" }
            return JapaneseReadingIndex(data, offsets, maxWordLength.coerceIn(1, 64))
        }

        private fun ByteArray.indexOf(value: Byte, start: Int = 0): Int {
            for (index in start until size) {
                if (this[index] == value) return index
            }
            return -1
        }
    }
}
