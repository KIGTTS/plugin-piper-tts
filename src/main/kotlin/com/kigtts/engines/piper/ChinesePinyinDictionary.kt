// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import java.io.File
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.nio.charset.StandardCharsets

internal class ChinesePinyinDictionary(assets: File) {
    private val assetRoot = assets
    private val index by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            File(assetRoot, ASSET_PATH).inputStream().use { source ->
                XZInputStream(source).use { compressed ->
                    ChinesePinyinIndex.fromBytes(compressed.readBytes())
                }
            }
        }.onFailure {
            Log.e("PiperReading", "Chinese pinyin dictionary load failed", it)
        }.getOrNull()
    }

    fun toPinyin(text: String): String? = index?.toPinyin(text)

    private companion object {
        const val ASSET_PATH = "chinese_pinyin/pypinyin_readings.tsv.xz"
    }
}

internal class ChinesePinyinIndex private constructor(
    private val data: ByteArray,
    private val lineOffsets: IntArray,
    private val maxWordLength: Int
) {
    fun toPinyin(text: String): String? {
        if (text.isEmpty()) return null
        val out = StringBuilder(text.length * 2)
        var offset = 0
        var converted = false
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (!isHan(codePoint)) {
                appendNonHan(out, codePoint)
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
                appendReading(out, matchedReading)
                offset = matchedEnd
                converted = true
            } else {
                out.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }
        return if (converted) out.toString().trim() else null
    }

    private fun appendReading(out: StringBuilder, reading: String) {
        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
        out.append(reading).append(' ')
    }

    private fun appendNonHan(out: StringBuilder, codePoint: Int) {
        val punctuation = when (codePoint) {
            0x3002 -> '.'
            0xFF0C -> ','
            0xFF01 -> '!'
            0xFF1F -> '?'
            0xFF1B -> ';'
            0xFF1A -> ':'
            else -> null
        }
        if (punctuation != null) {
            if (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
            out.append(punctuation).append(' ')
        } else {
            out.appendCodePoint(codePoint)
        }
    }

    private fun candidateEnds(text: String, start: Int): List<Int> {
        val ends = ArrayList<Int>(maxWordLength)
        var offset = start
        var count = 0
        while (offset < text.length && count < maxWordLength) {
            val codePoint = text.codePointAt(offset)
            if (!isHan(codePoint)) break
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

    internal companion object {
        private const val MAGIC = "#KIGTTS-ZH-PINYIN-1"
        private const val TAB: Byte = 0x09
        private const val NEWLINE: Byte = 0x0A

        fun fromBytes(data: ByteArray): ChinesePinyinIndex {
            val headerEnd = data.indexOf(NEWLINE)
            require(headerEnd > 0) { "中文拼音词典缺少文件头" }
            val header = String(data, 0, headerEnd, StandardCharsets.UTF_8).split('\t')
            require(header.size == 3 && header[0] == MAGIC) { "中文拼音词典格式不受支持" }
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
            require(line == entryCount) { "中文拼音词典条目数量不匹配" }
            return ChinesePinyinIndex(data, offsets, maxWordLength.coerceIn(1, 32))
        }

        private fun ByteArray.indexOf(value: Byte, start: Int = 0): Int {
            for (index in start until size) {
                if (this[index] == value) return index
            }
            return -1
        }
    }
}
