package com.yuyan.imemodule.hotword

/**
 * 语音热词库 —— 数据模型。
 *
 * 文件结构（仿照 CapsWriter-Offline，稍作规整）：
 *  ┌─ 主配置 voice_config.txt：启用哪些词库、执行顺序、发音相似度阈值、交互模式等
 *  ├─ 词库文件1（如 wordbook_default.txt）：[regex] 段（正则规则）+ [hotword] 段（热词/近音）
 *  ├─ 词库文件2（如 wordbook_tech.txt）
 *  └─ ...
 */

/** 一条正则规则：pattern 匹配 → 替换为 replacement */
data class RegexRule(val pattern: String, val replacement: String)

/**
 * 一个“热词”条目。
 * @param canonical 规范写法（最终替换目标）
 * @param aliases   别名/近音写法（识别可能输出的样子）
 * @param blacklist 黑名单：#在 `~~~` 后，若其出现在触发替换的位置附近，则不替换，避免误伤
 */
data class WordEntry(
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val blacklist: List<String> = emptyList()
) {
    /** 参与匹配的全部写法（规范 + 别名），去空、去重 */
    val variants: List<String> by lazy {
        (listOf(canonical) + aliases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/** 一个词库文件解析后的内容 */
class Glossary(val name: String) {
    val regexRules = mutableListOf<RegexRule>()
    val hotwords = mutableListOf<WordEntry>()
}

/** 主配置解析结果 */
data class GlossaryConfig(
    val enabled: Boolean = true,
    val interactHoldA: Boolean? = null,          // 语音识别A：true=长按 false=点按；null=未指定（用设置）
    val interactHoldB: Boolean? = null,          // 语音识别B：同上
    val applyRegexFirst: Boolean = true,       // 是否先正则后热词
    val similarityThreshold: Float = 0.8f,     // M3 热词相似度阈值
    val enabledWordbooks: List<String> = emptyList() // 启用的词库名（顺序即优先级）
) {
    companion object { const val VOICE_CONFIG_FILE = "voice_config.txt" }
}

/** 当前生效的完整配置：主配置 + 已启用的各词库 */
class GlossarySuite(val config: GlossaryConfig, val glossaries: List<Glossary>) {
    val isUsable: Boolean
        get() = config.enabled && glossaries.isNotEmpty()
}