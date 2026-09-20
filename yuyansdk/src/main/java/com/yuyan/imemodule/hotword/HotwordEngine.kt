package com.yuyan.imemodule.hotword

/**
 * 语音识别结果的后处理引擎。
 *
 * M2：正则替换 + “别名精确匹配”热词替换（够用、可离线验证）。
 * M3：把 [hotwordMatcher] 换成基于发音相似度的实现，正文替换逻辑不变。
 */
class HotwordEngine(private val suite: GlossarySuite) {

    /** 热词替换策略；M2 用精确子串，M3 替换为发音相似度实现 */
    interface HotwordMatcher {
        fun replace(text: String, entry: WordEntry): String
    }

    private val regexRules: List<RegexRule> = suite.glossaries.flatMap { it.regexRules }
    private val hotwords: List<WordEntry> = suite.glossaries.flatMap { it.hotwords }

    /** 注入点：M3 换成语义近似/发音相似实现（默认精确匹配） */
    var hotwordMatcher: HotwordMatcher = SimpleSubstringMatcher

    /** 处理一段识别结果，返回替换后的文本 */
    fun process(text: String): String {
        var out = text
        val regexFirst = suite.config.applyRegexFirst
        if (regexFirst) {
            out = applyRegex(out)
            out = applyHotwords(out)
        } else {
            out = applyHotwords(out)
            out = applyRegex(out)
        }
        return out
    }

    private fun applyRegex(text: String): String {
        var out = text
        for (rule in regexRules) {
            try {
                out = Regex(rule.pattern).replace(out, rule.replacement)
            } catch (_: Throwable) {
                // 单个规则出错不应使整段文本失效，跳过
            }
        }
        return out
    }

    private fun applyHotwords(text: String): String {
        var out = text
        for (entry in hotwords) {
            out = hotwordMatcher.replace(out, entry)
        }
        return out
    }

    /** 精确子串替换策略：把命中别名/规范的地方统一为规范写法，空串一律不动 */
    private object SimpleSubstringMatcher : HotwordMatcher {
        override fun replace(text: String, entry: WordEntry): String {
            if (text.isEmpty()) return text
            val hasBlacklistHit = entry.blacklist.any { b -> b.isNotEmpty() && text.contains(b, ignoreCase = true) }
            if (hasBlacklistHit) return text

            var out = text
            for (variant in entry.variants) {
                if (variant.isEmpty() || variant == entry.canonical) continue
                try {
                    val re = Regex(Regex.escape(variant), RegexOption.IGNORE_CASE)
                    out = re.replace(out) { entry.canonical }
                } catch (_: Throwable) {
                }
            }
            return out
        }
    }
}