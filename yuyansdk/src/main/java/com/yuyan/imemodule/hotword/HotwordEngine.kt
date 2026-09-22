package com.yuyan.imemodule.hotword

/**
 * 语音识别结果的后处理引擎（正则替换 + 热词替换）。
 *
 * 热词流程（《热词规则语法开发手册》§4，判定顺序即优先级）：
 *   ┌─ 匹配：匹配器对 rule.aliases 在整句文本中检索，得到候选命中区间
 *   ├─ 过滤：① 全文黑名单（整句包含任一词 → 规则失效）
 *   │        ② 全文白名单（非空且整句不含任一 → 规则失效）
 *   │        ③ 局部黑名单（命中区间邻域窗口内出现任一词 → 该处丢弃）
 *   ├─ 选词：条件块（整句包含条件词，首个命中的块生效）→ 替换词；否则默认目标词
 *   └─ 替换：命中按 (分数降序, 区间长度降序) 排序，区间不重叠、原文==替换词丢弃，
 *            跨规则统一决策后从后往前替换。
 *
 * 正则规则沿用旧行为：pattern = replacement 整句替换；黑/白名单（! / +）为整句全文判断。
 * 全文黑/白名单、条件判断均基于**整句原始识别文本**（大小写不敏感，不受前面规则已替换内容影响）。
 */
class HotwordEngine(private val suite: GlossarySuite) {

    /** 匹配器：在 text 中检索一条热词规则的匹配词，返回命中区间（字符下标，含头不含尾） */
    interface HotwordMatcher {
        fun search(entry: WordEntry, text: String): List<MatchSpan>
    }

    /** 一处命中：区间 + 匹配分数（精确子串=1.0；发音相似=相似度 0~1） */
    data class MatchSpan(val start: Int, val end: Int, val score: Float = 1.0f)

    /** 待替换候选：区间 + 分数 + 替换词 */
    private data class Candidate(val start: Int, val end: Int, val score: Float, val replacement: String)

    private val glossaries: List<Glossary> = suite.glossaries

    /**
     * 启用的词库名集合（小写规范化）；null = 全部启用。
     * enabled_wordbooks 留空 = 全部启用；非空时 = enabled 列表 ∪ include_wordbooks 强制纳入列表。
     */
    private val enabledSet: Set<String>? =
        if (suite.config.enabledWordbooks.isEmpty()) {
            null
        } else {
            (suite.config.enabledWordbooks + suite.config.includeWordbooks).map(::normName).toSet()
        }

    private fun normName(s: String): String {
        var n = s.trim().lowercase()
        if (n.endsWith(".txt")) n = n.dropLast(4)
        return n
    }

    /** 该词库是否启用（每次替换前检查设置） */
    private fun isGlossaryEnabled(g: Glossary): Boolean =
        enabledSet == null || enabledSet.contains(normName(g.name))

    /** 注入点：默认精确子串；可在外部换成发音相似实现 */
    var hotwordMatcher: HotwordMatcher = SimpleSubstringMatcher

    /** 局部黑名单邻域窗口宽度（字符数，手册建议默认 5） */
    var localWindow: Int = 5

    /** 处理一段识别结果，返回替换后的文本 */
    fun process(text: String): String {
        val original = text
        var out = text
        val regexFirst = suite.config.applyRegexFirst
        if (regexFirst) {
            out = applyRegex(original, out)
            out = applyHotwords(original, out)
        } else {
            out = applyHotwords(original, out)
            out = applyRegex(original, out)
        }
        return out
    }

    /**
     * 全文黑/白名单门控（针对整句**原始**识别文本，位置无关）：
     * 黑名单：原始输入任何位置包含任一黑名单词汇/字符 → 跳过（不替换）；
     * 白名单：原始输入不含任何白名单词汇/字符 → 跳过；两者同时出现时需同时满足。
     */
    private fun isBlocked(original: String, blacklist: List<String>, whitelist: List<String>): Boolean {
        if (blacklist.any { it.isNotEmpty() && original.contains(it, ignoreCase = true) }) return true
        if (whitelist.isNotEmpty() && !whitelist.any { it.isNotEmpty() && original.contains(it, ignoreCase = true) }) return true
        return false
    }

    private fun applyRegex(original: String, text: String): String {
        var out = text
        for (g in glossaries) {
            if (!isGlossaryEnabled(g)) continue
            for (rule in g.regexRules) {
                if (isBlocked(original, rule.blacklist, rule.whitelist)) continue
                try {
                    out = Regex(rule.pattern).replace(out, rule.replacement)
                } catch (_: Throwable) {
                    // 单个规则出错不应使整段文本失效，跳过
                }
            }
        }
        return out
    }

    /**
     * 热词替换：匹配 → 过滤 → 选词 → 替换（手册 §4/§5）。
     * 匹配/命中区间与最终替换都基于本阶段文本 [text]（正则先执行时即正则后的文本），
     * 全文黑/白名单与条件判断基于整句**原始**识别文本 [original]（不受前面规则已替换内容影响）。
     */
    private fun applyHotwords(original: String, text: String): String {
        val candidates = mutableListOf<Candidate>()
        for (g in glossaries) {
            if (!isGlossaryEnabled(g)) continue
            for (entry in g.hotwords) {
                // 过滤：全文黑名单 / 全文白名单（基于整句原始文本）
                if (isBlocked(original, entry.globalBlacklist, entry.globalWhitelist)) continue
                // 匹配：在本阶段文本中检索匹配词 → 候选命中区间
                val spans = try {
                    hotwordMatcher.search(entry, text)
                } catch (t: Throwable) {
                    emptyList()
                }
                if (spans.isEmpty()) continue
                // 选词：条件块（首个命中生效），否则默认目标词
                val replacement = chooseReplacement(original, entry)
                // 过滤：局部黑名单（逐命中邻域）→ 重叠 → 原文==替换词（无变化）
                val occupied = mutableListOf<Pair<Int, Int>>()
                for (span in spans.sortedWith(
                    compareByDescending<MatchSpan> { it.score }.thenByDescending { it.end - it.start }
                )) {
                    if (span.start < 0 || span.end > text.length || span.end <= span.start) continue
                    if (localBlacklistHit(text, span, entry.localBlacklist)) continue
                    if (occupied.any { !(span.end <= it.first || span.start >= it.second) }) continue
                    if (text.substring(span.start, span.end) == replacement) continue
                    occupied.add(span.start to span.end)
                    candidates.add(Candidate(span.start, span.end, span.score, replacement))
                }
            }
        }
        // 跨规则统一决策：分数降序 → 区间长度降序；占用区间不重叠
        candidates.sortWith(
            compareByDescending<Candidate> { it.score }.thenByDescending { it.end - it.start }
        )
        val occupied = mutableListOf<Pair<Int, Int>>()
        val finalCandidates = mutableListOf<Candidate>()
        for (c in candidates) {
            if (occupied.any { !(c.end <= it.first || c.start >= it.second) }) continue
            occupied.add(c.start to c.end)
            finalCandidates.add(c)
        }
        // 从后往前替换，避免索引失效
        var out = text
        for (c in finalCandidates.sortedByDescending { it.start }) {
            out = out.substring(0, c.start) + c.replacement + out.substring(c.end)
        }
        return out
    }

    /** 选词：按条件块顺序取第一个「整句包含其任一条件词」的块；都不命中 → 默认目标词 */
    private fun chooseReplacement(original: String, entry: WordEntry): String {
        if (entry.conditions.isEmpty()) return entry.target
        val low = original.lowercase()
        for ((condWords, repl) in entry.conditions) {
            if (condWords.any { it.isNotEmpty() && low.contains(it.lowercase()) }) return repl
        }
        return entry.target
    }

    /** 局部黑名单：命中区间左右各 [localWindow] 个字符的邻域窗口内出现任一黑名单词 → 该处命中丢弃 */
    private fun localBlacklistHit(text: String, span: MatchSpan, blacklist: Set<String>): Boolean {
        if (blacklist.isEmpty()) return false
        val from = (span.start - localWindow).coerceAtLeast(0)
        val to = (span.end + localWindow).coerceAtMost(text.length)
        val ctx = text.substring(from, to).lowercase()
        return blacklist.any { it.isNotEmpty() && ctx.contains(it.lowercase()) }
    }

    /** 精确子串匹配策略（手册 §5 ExactSubstringMatcher）：大小写不敏感，返回全部命中区间 */
    private object SimpleSubstringMatcher : HotwordMatcher {
        override fun search(entry: WordEntry, text: String): List<MatchSpan> {
            if (text.isEmpty()) return emptyList()
            val low = text.lowercase()
            val spans = mutableListOf<MatchSpan>()
            for (alias in entry.aliases) {
                if (alias.isEmpty()) continue
                val al = alias.lowercase()
                var start = 0
                while (true) {
                    val i = low.indexOf(al, start)
                    if (i < 0) break
                    spans.add(MatchSpan(i, i + alias.length, 1.0f))
                    start = i + alias.length
                }
            }
            return spans
        }
    }
}
