package com.yuyan.imemodule.hotword

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * 发音相似度热词匹配器（M3）。
 *
 * 思路（移植自 CapsWriter-Offline 的音素方案）：
 *  1. 把文本与词条都“音素化”：中文每字 → 带声调的拼音音节；英文/数字 → 原样小写；
 *  2. 在识别文本上按词条的“token 数”滑动窗口，计算窗口音素串与词条音素串的相似度；
 *  3. 相似度 ≥ 阈值时，把对应文字区间替换成词条规范写法。
 *
 * 相似度用“归一化编辑距离”：1 - Levenshtein(窗口音素串, 词条音素串) / 较长串长度。
 */
class PhoneticMatcher(private val threshold: Float) : HotwordEngine.HotwordMatcher {

    /** 一段文本里的一个“音素单元”（一个汉字 = 一个单元，一个英文/数字词 = 一个单元） */
    private class Unit(val text: String, val phonetic: String, val charStart: Int) {
        val charEnd: Int get() = charStart + text.length
    }

    override fun replace(text: String, entry: WordEntry): String {
        if (text.isEmpty()) return text
        // 黑名单命中任意一个，就不做这条替换（保守防误伤）
        val blacklistHit = entry.blacklist.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
        if (blacklistHit) return text

        var out = text
        for (variant in entry.variants) {
            if (variant.isEmpty() || variant == entry.canonical) continue
            val matched = tryMatch(out, variant)
            if (matched != null) {
                out = out.substring(0, matched.first) + entry.canonical + out.substring(matched.second)
            }
        }
        return out
    }

    /** 在 text 中找发音最接近 variant 的区间；找不到返回 null。返回 [start, end) 字符下标 */
    private fun tryMatch(text: String, variant: String): Pair<Int, Int>? {
        val units = toUnits(text)
        val variantUnits = toUnits(variant)
        if (units.isEmpty() || variantUnits.isEmpty()) return null
        val variantPhon = variantUnits.joinToString("") { it.phonetic }
        if (variantPhon.isEmpty()) return null

        // 允许窗口长度在词条 token 数 ±1 内浮动，容忍“多/少一个字”的识别偏差
        val base = variantUnits.size
        val windowLengths = (base - 1..base + 1).filter { it in 1..units.size }

        var bestSim = -1.0
        var bestStart = 0
        var bestEnd = 0
        for (len in windowLengths) {
            for (start in 0..units.size - len) {
                val end = start + len
                val winPhon = (start until end).joinToString("") { units[it].phonetic }
                val sim = similarity(winPhon, variantPhon)
                if (sim > bestSim) {
                    bestSim = sim
                    bestStart = units[start].charStart
                    bestEnd = units[end - 1].charEnd
                }
            }
        }
        return if (bestSim >= threshold) bestStart to bestEnd else null
    }

    /** 文本音素化：汉字→拼音(带声调)，英文/数字→小写原文；标点跳过 */
    private fun toUnits(text: String): List<Unit> {
        val units = mutableListOf<Unit>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                isHan(c) -> {
                    units.add(Unit(text.substring(i, i + 1), pinyinOf(c), i))
                    i++
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && text[j].isLetter()) j++
                    val word = text.substring(i, j)
                    units.add(Unit(word, word.lowercase(), i))
                    i = j
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && text[j].isDigit()) j++
                    val num = text.substring(i, j)
                    units.add(Unit(num, num, i))
                    i = j
                }
                else -> i++
            }
        }
        return units
    }

    private fun pinyinOf(c: Char): String {
        return try {
            val arr = PinyinHelper.toHanyuPinyinStringArray(c)
            if (arr.isNullOrEmpty()) c.lowercaseChar().toString() else arr[0].trim().lowercase()
        } catch (_: Throwable) {
            c.lowercaseChar().toString()
        }
    }

    /** 归一化编辑距离相似度：1 完全相同，0 完全不同 */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - dist.toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            val ai = a[i - 1]
            for (j in 1..n) {
                val cost = if (ai == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }

    private fun isHan(c: Char): Boolean = c in '\u4e00'..'\u9fff'
}