package com.yuyan.imemodule.hotword

/**
 * 词库/主配置 文本解析器。
 *
 * 语法（`#` 开头为注释，忽略空行）：
 *
 *  主配置 voice_config.txt：
 *    enable_voice = true
 *    interact_mode = tap            # tap=点按录音  hold=按住录音
 *    similarity_threshold = 0.8     # 0~1，热词发音相似度阈值（M3 用）
 *    apply_order = regex,hotword    # 先正则后热词
 *    enabled_wordbooks = default,tech,game
 *
 *  词库文件：
 *    [regex]
 *    毫安时 = mAh
 *    负一 = -1
 *    (艾特)\s*(\w+)\s*(点)\s*(\w+) = @\1.\3
 *
 *    [hotword]
 *    CapsWriter = CapsWriter | Caps Rider
 *    Claude = Claude | 克劳德 | 克劳得 | cloud ~~~ weather | sky
 */
object GlossaryParser {

    /** 解析主配置 */
    fun parseConfig(text: String): GlossaryConfig {
        var enabled = true
        var interactHoldA: Boolean? = null
        var interactHoldB: Boolean? = null
        var applyRegexFirst = true
        var threshold = 0.8f
        var wordbooks: List<String> = emptyList()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "enable_voice" -> enabled = parseBool(value, enabled)
                "interact_mode" -> {
                    val hold = when (value.lowercase()) {
                        "hold", "press", "long", "长按", "按住" -> true
                        "tap", "click", "点按" -> false
                        else -> null
                    }
                    interactHoldA = hold
                    interactHoldB = hold
                }
                "interact_mode_a" -> interactHoldA = when (value.lowercase()) {
                    "hold", "press", "long", "长按", "按住" -> true
                    "tap", "click", "点按" -> false
                    else -> null
                }
                "interact_mode_b" -> interactHoldB = when (value.lowercase()) {
                    "hold", "press", "long", "长按", "按住" -> true
                    "tap", "click", "点按" -> false
                    else -> null
                }
                "similarity_threshold" -> value.toFloatOrNull()?.let { threshold = it.coerceIn(0f, 1f) }
                "apply_order" -> {
                    val lower = value.lowercase()
                    applyRegexFirst = if (lower.contains("regex")) !lower.startsWith("hotword") else true
                }
                "enabled_wordbooks" -> wordbooks = value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return GlossaryConfig(
            enabled = enabled,
            interactHoldA = interactHoldA,
            interactHoldB = interactHoldB,
            applyRegexFirst = applyRegexFirst,
            similarityThreshold = threshold,
            enabledWordbooks = wordbooks
        )
    }

    /** 解析单个词库 */
    fun parseGlossary(name: String, text: String): Glossary {
        val g = Glossary(name)
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            when (section) {
                "regex" -> parseRegexLine(line)?.let { g.regexRules.add(it) }
                "hotword", "热词", "词" -> parseHotwordLine(line)?.let { g.hotwords.add(it) }
            }
        }
        return g
    }

    private fun parseRegexLine(line: String): RegexRule? {
        val idx = line.indexOf('=')
        if (idx < 0) return null
        val pattern = line.substring(0, idx).trim()
        val replacement = line.substring(idx + 1).trim().replace("\\s", " ")
        if (pattern.isEmpty()) return null
        return RegexRule(pattern, replacement)
    }

    private fun parseHotwordLine(line: String): WordEntry? {
        val (left, right) = line.split("~~~", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }
        val variants = left.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (variants.isEmpty()) return null
        val blacklist = right.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return WordEntry(
            canonical = variants[0],
            aliases = variants.drop(1),
            blacklist = blacklist
        )
    }

    private fun parseBool(value: String, default: Boolean): Boolean = when (value.lowercase()) {
        "true", "1", "yes", "on", "是", "开" -> true
        "false", "0", "no", "off", "否", "关" -> false
        else -> default
    }
}