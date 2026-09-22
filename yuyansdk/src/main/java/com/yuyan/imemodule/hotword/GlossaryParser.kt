package com.yuyan.imemodule.hotword

/**
 * 词库/主配置 文本解析器。
 *
 * 语法（`#` 开头为注释，忽略空行）：
 *
 *  主配置 voice_config.txt（结构与原版保持一致）：
 *    config_version = 3            # 配置格式版本；版本低于程序期望时自动重建并提示
 *    enable_voice = true
 *    interact_mode = tap            # tap=点按录音  hold=按住录音
 *    similarity_threshold = 0.8     # 0~1，热词发音相似度阈值
 *    apply_order = regex,hotword    # 先正则后热词
 *    enabled_wordbooks = 默认词库,技术词库   # 启用的词库名（[[词库名]] 的名字），逗号分隔；留空=全部启用
 *    include_wordbooks = 词库A,词库B        # 强制纳入（忽略大小写），不受上方过滤；留空=不使用
 *
 *  词库文件（推荐：一个文件里用 [[词库名]] 划分多个词库，名称任意，只要被 [[ ]] 包裹）：
 *    [[默认词库]]
 *    毫安时 = mAh                                     # 旧式：精确/正则替换
 *    Claude | 克劳德 | 克劳得 | cloud ! 天气|气象       # 新手册语法：热词行（无等号）
 *
 *  ── 热词行新语法（《热词规则语法开发手册》§1/§2/§3）──
 *    目标词 | 匹配词1 | 匹配词2 [~~~ 局部黑名单] [! 全文黑名单] [+ 全文白名单] [条件词X:替换词A1]
 *    · 主表达式第一项=目标词（默认替换结果），其余=匹配词（命中任意一个即触发；匹配词允许含空白）
 *    · ~~~ 局部黑名单：命中位置邻域窗口（左右各 local_window 字符）内出现任一词 → 该处不替换
 *    · ! 全文黑名单：整句出现任一词 → 本规则整体失效（优先级最高）
 *    · + 全文白名单：整句必须出现至少一个词 → 否则本规则整体失效
 *    · [条件词1|条件词2:替换词]：整句包含条件词（| 为 OR）→ 本次替换结果改用替换词（首个命中的块生效）
 *    解析顺序固定：先提条件块 → 再切 ! / + 全文段 → 再切 ~~~ 局部黑名单 → 最后解析主表达式（手册 §3）。
 *
 *  ── 旧式兼容（含 = 的行，语义与原版一致，保证老配置文件直接可用）──
 *    pattern = replacement [~~~ 黑名单] [! 黑名单] [+ 白名单]     # 替换内容含 | → 近音热词；否则 → 正则
 *    · 旧式行首修饰符（!黑 +白 规则 = 替换）继续支持；
 *    · 旧式 ~~~ 黑名单按“整句包含即不替换”（全文语义）并入全文黑名单；
 *    · 旧式 [regex] / [hotword] / [热词] / [词] 段标记继续支持（强制类型）；
 *    · 无 [[ ]] 标记的旧式单词库文件：整文件算一个词库（名字 = 文件名）。
 */
object GlossaryParser {

    /** 条件块：形如 [条件词1|条件词2:替换词] */
    private val COND_RE = Regex("\\[([^\\]]+)\\]")

    /** 全文段切分：在 ! / + 且其前一个字符是行首或空白的位置分割（手册 §3 步骤2） */
    private val GLOBAL_SPLIT_RE = Regex("(?<![^\\s])(?=[!+])")

    /** 解析主配置 */
    fun parseConfig(text: String): GlossaryConfig {
        var version = 0
        var enabled = true
        var interactHoldA: Boolean? = null
        var interactHoldB: Boolean? = null
        var applyRegexFirst = true
        var threshold = 0.8f
        var wordbooks: List<String> = emptyList()
        var includes: List<String> = emptyList()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "config_version" -> version = value.toIntOrNull() ?: 0
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
                "include_wordbooks" -> includes = value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return GlossaryConfig(
            configVersion = version,
            enabled = enabled,
            interactHoldA = interactHoldA,
            interactHoldB = interactHoldB,
            applyRegexFirst = applyRegexFirst,
            similarityThreshold = threshold,
            enabledWordbooks = wordbooks,
            includeWordbooks = includes
        )
    }

    /**
     * 解析一个词库文件，返回其中的全部词库（`[[词库名]]` 段）。
     * 文件里没有 `[[ ]]` 标记时视为旧式单词库文件：整个文件 = 一个词库（名字 = 文件名）。
     */
    fun parseGlossary(fileName: String, text: String): List<Glossary> {
        val lines = text.lineSequence().toList()
        val headers = mutableListOf<Pair<Int, String>>() // (行号, 词库名)
        lines.forEachIndexed { i, raw ->
            val t = raw.trim()
            if (t.startsWith("[[") && t.endsWith("]]") && t.length > 4) {
                headers.add(i to t.substring(2, t.length - 2).trim())
            }
        }

        val result = mutableListOf<Glossary>()
        val preHeader = mutableListOf<String>()   // 第一个 [[词库名]] 之前的规则行，归第一段
        var current: Glossary? = null
        var mode = 0                              // 0=自动 1=强制正则 2=强制热词（仅旧式 = 行生效）
        var hi = 0

        fun parseInto(g: Glossary, raw: String) {
            val t = raw.trim()
            when (t.lowercase()) {
                "[regex]" -> { mode = 1; return }
                "[hotword]", "[热词]", "[词]" -> { mode = 2; return }
            }
            // 行内是否有 =（旧式语法标记）；无 = → 手册新语法热词行
            if (t.indexOf('=') < 0) {
                // 手册新语法热词行：目标词 | 匹配词 [~~~ 局部黑] [! 全文黑] [+ 全文白] [条件:替换]
                parseManualRule(t)?.let { g.hotwords.add(it) }
                return
            }
            // 旧式语法（含 =）：支持行首 !/+/~~~ 修饰符与 auto 类型判定
            val (leadBlack, leadWhite, ruleLine) = parseLeadingModifiers(t)
            if (ruleLine.isEmpty()) return
            val (main, legacy) = ruleLine.split("~~~", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }
            // 旧式 ~~~ 为“整句包含即不替换”的全文语义（保持原版行为），并入全文黑名单
            val legacyBlacklist = legacy.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val eq = main.indexOf('=')
            if (eq < 0) {
                // 无等号旧式行（防御）：整行按 目标词 | 匹配词 处理
                val variants = main.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (variants.isEmpty()) return
                g.hotwords.add(
                    WordEntry(
                        target = variants[0],
                        aliases = variants,
                        globalBlacklist = (legacyBlacklist + leadBlack).distinct(),
                        globalWhitelist = leadWhite
                    )
                )
                return
            }
            val patternPart = main.substring(0, eq).trim()
            if (patternPart.isEmpty()) return
            val rhs = main.substring(eq + 1)
            val (trailBlack, trailWhite, content) = parseTrailingModifiers(rhs)
            val blackAll = (trailBlack + legacyBlacklist + leadBlack).distinct()
            val whiteAll = (trailWhite + leadWhite).distinct()
            val isHotword = when (mode) {
                1 -> false
                2 -> true
                else -> content.contains('|')
            }
            if (isHotword) {
                val aliases = content.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                g.hotwords.add(
                    WordEntry(
                        target = patternPart,
                        aliases = (listOf(patternPart) + aliases).distinct(),
                        globalBlacklist = blackAll,
                        globalWhitelist = whiteAll
                    )
                )
            } else {
                g.regexRules.add(RegexRule(patternPart, content.replace("\\s", " "), blackAll, whiteAll))
            }
        }

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (hi < headers.size && i == headers[hi].first) {
                current = Glossary(headers[hi].second, fileName)
                mode = 0
                if (result.isEmpty()) {
                    preHeader.forEach { parseInto(current!!, it) }
                    preHeader.clear()
                }
                result.add(current)
                hi++
                continue
            }
            if (headers.isNotEmpty() && current == null) {
                preHeader.add(line)
                continue
            }
            val g = current ?: Glossary(fileName, fileName).also {
                result.add(it)
                current = it
            }
            parseInto(g, line)
        }
        return result
    }

    /**
     * 解析手册新语法热词行（无等号）。严格按手册 §3 的顺序：
     *   1) 提取条件块 [ ... ]（块内首个 ':' 分割，左=条件词 | 切分，右=替换词，均非空才保留）；
     *   2) 在「! / + 且其前一个字符是行首或空白」处切出全文黑/白名单段（段内空白全部忽略）；
     *   3) 切分 ~~~ 局部黑名单；
     *   4) 解析主表达式：第一项=目标词，其余=匹配词（匹配词允许含空白）。
     * 无效行（主表达式为空等）返回 null。
     */
    private fun parseManualRule(line: String): WordEntry? {
        var rest = line

        // 步骤1：条件块
        val conditions = mutableListOf<Pair<List<String>, String>>()
        while (true) {
            val m = COND_RE.find(rest) ?: break
            val block = m.groupValues[1]
            rest = rest.removeRange(m.range.first, m.range.last + 1)
            val colon = block.indexOf(':')
            if (colon > 0) {
                val condWords = block.substring(0, colon).split('|').map { it.trim() }.filter { it.isNotEmpty() }
                val repl = block.substring(colon + 1).trim()
                if (condWords.isNotEmpty() && repl.isNotEmpty()) {
                    conditions.add(condWords to repl)
                }
            }
        }

        // 步骤2：全文黑名单 ! / 全文白名单 +
        // 注意：Java 正则的 split 不会在行首（位置 0）切分；而手册规定「! / + 且其前一个字符是
        // 行首或空白」即为全文段边界，行首 ! / + 意味着主表达式为空 → 该行无效（对应手册 P6）。
        if (rest.isNotBlank() && (rest.trimStart().startsWith("!") || rest.trimStart().startsWith("+"))) {
            return null
        }
        var globalBlack = emptyList<String>()
        var globalWhite = emptyList<String>()
        val segments = GLOBAL_SPLIT_RE.split(rest)
        if (segments.size > 1) {
            rest = segments[0]
            for (seg in segments.drop(1)) {
                val prefix = seg[0]
                val content = seg.substring(1).filterNot { it.isWhitespace() }
                val words = content.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (prefix == '!') globalBlack += words else globalWhite += words
            }
        }

        // 步骤3：~~~ 局部黑名单
        var localBlack = emptySet<String>()
        val tilde = rest.indexOf("~~~")
        if (tilde >= 0) {
            localBlack = rest.substring(tilde + 3).split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            rest = rest.substring(0, tilde)
        }

        // 步骤4：主表达式
        val parts = rest.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return WordEntry(
            target = parts[0],
            aliases = parts,
            localBlacklist = localBlack,
            globalBlacklist = globalBlack,
            globalWhitelist = globalWhite,
            conditions = conditions
        )
    }

    /**
     * 解析行首修饰符（旧式写法，向后兼容）：
     *  `!词1|词2` 黑名单；`+词1|词2` 白名单（可多个，空格分隔）。
     * 返回 (黑名单, 白名单, 剩余规则行)。
     */
    private fun parseLeadingModifiers(line: String): Triple<List<String>, List<String>, String> {
        val black = mutableListOf<String>()
        val white = mutableListOf<String>()
        var rest = line
        var changed = true
        while (changed && rest.isNotEmpty()) {
            changed = false
            val t = rest.trimStart()
            if (t.isEmpty()) break
            val c = t[0]
            if (c != '!' && c != '+') break
            val sp = t.indexOf(' ')
            val token = if (sp < 0) t else t.substring(0, sp)
            val words = token.substring(1).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (c == '!') black += words else white += words
            rest = if (sp < 0) "" else t.substring(sp + 1).trimStart()
            changed = true
        }
        return Triple(black.distinct(), white.distinct(), rest)
    }

    /**
     * 从规则“替换内容”末尾解析尾置修饰符（旧式推荐写法）：
     *  `... 替换 !词1|词2 +词3|词4`。
     * 规则：从末尾往回收取以 `!`/`+` 开头的空白分隔 token 作为修饰符，
     * 其余部分原样保留为替换内容（内容里的空格会原样保留）。
     * 返回 (黑名单, 白名单, 纯替换内容)。
     */
    private fun parseTrailingModifiers(rhs: String): Triple<List<String>, List<String>, String> {
        val black = mutableListOf<String>()
        val white = mutableListOf<String>()
        val s = rhs.trim()
        if (s.isEmpty()) return Triple(emptyList(), emptyList(), "")
        val tokens = s.split(' ')
        var i = tokens.size - 1
        while (i >= 0) {
            val tok = tokens[i]
            if (tok.isEmpty()) {
                i--
                continue
            }
            val c = tok[0]
            if (c != '!' && c != '+') break
            val words = tok.substring(1).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (c == '!') black += words else white += words
            i--
        }
        val content = tokens.take(i + 1).joinToString(" ").trim()
        return Triple(black.distinct(), white.distinct(), content)
    }

    private fun parseBool(value: String, default: Boolean): Boolean = when (value.lowercase()) {
        "true", "1", "yes", "on", "是", "开" -> true
        "false", "0", "no", "off", "否", "关" -> false
        else -> default
    }
}
