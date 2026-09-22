package com.yuyan.imemodule.hotword

/**
 * 语音热词库 —— 数据模型。
 *
 * 文件结构（仿照 CapsWriter-Offline，稍作规整）：
 *  ┌─ 主配置 voice_config.txt：启用哪些词库、执行顺序、发音相似度阈值、交互模式等
 *  ├─ 词库文件1（如 wordbook_default.txt）：[regex] 段（正则规则）+ 热词规则（[[词库名]] 段内）
 *  ├─ 词库文件2（如 wordbook_tech.txt）
 *  └─ ...
 *
 * 热词规则语法（《热词规则语法开发手册》v1.0）：
 *   目标词 | 匹配词1 | 匹配词2 [~~~ 局部黑名单] [! 全文黑名单] [+ 全文白名单] [条件词X:替换词A1]
 *   主表达式第一项 = 目标词（默认替换结果），其余 = 匹配词（命中任意一个即触发）。
 */

/**
 * 一条正则规则：pattern 匹配 → 替换为 replacement。
 * @param blacklist 全文黑名单（`!` 修饰符，兼容旧语法）：本次输入包含其中任一词汇/字符时不替换
 * @param whitelist 全文白名单（`+` 修饰符）：本次输入必须包含其中至少一个词汇/字符才替换
 */
data class RegexRule(
    val pattern: String,
    val replacement: String,
    val blacklist: List<String> = emptyList(),
    val whitelist: List<String> = emptyList()
)

/**
 * 一个“热词”条目（手册 §1/§3 解析产物）。
 * @param target            目标词（默认替换结果；主表达式第一项）
 * @param aliases           匹配词列表（含第一个 = 目标词；命中任意一个即触发替换）
 * @param localBlacklist    ~~~ 局部黑名单：命中位置邻域窗口内出现任一词 → 该处不替换
 * @param globalBlacklist   ! 全文黑名单：整句出现任一词 → 本规则整体失效（优先级最高）
 * @param globalWhitelist   + 全文白名单：整句必须出现至少一个词 → 否则本规则整体失效
 * @param conditions        条件块 [(条件词列表, 替换词)]：整句包含条件词（| 为 OR）→
 *                          本次替换结果改用该替换词（按行内顺序取第一个命中的块）
 */
data class WordEntry(
    val target: String,
    val aliases: List<String> = emptyList(),
    val localBlacklist: Set<String> = emptySet(),
    val globalBlacklist: List<String> = emptyList(),
    val globalWhitelist: List<String> = emptyList(),
    val conditions: List<Pair<List<String>, String>> = emptyList()
)

/**
 * 一个“词库”（词库文件里的一个 `[[词库名]]` 段；旧式无 `[[ ]]` 标记的文件则整个文件算一个词库）。
 * @param name        词库名（`[[词库一]]` 中的“词库一”；旧式文件 = 文件名）
 * @param sourceFile  来源文件名（仅展示用，如 wordbook_default）
 */
class Glossary(val name: String, val sourceFile: String = "") {
    val regexRules = mutableListOf<RegexRule>()
    val hotwords = mutableListOf<WordEntry>()
}

/** 主配置解析结果 */
data class GlossaryConfig(
    val configVersion: Int = 0,                // 配置格式版本（voice_config.txt 的 config_version 字段）
    val enabled: Boolean = true,
    val interactHoldA: Boolean? = null,          // 语音识别A：true=长按 false=点按；null=未指定（用设置）
    val interactHoldB: Boolean? = null,          // 语音识别B：同上
    val applyRegexFirst: Boolean = true,       // 是否先正则后热词
    val similarityThreshold: Float = 0.8f,     // M3 热词相似度阈值
    val enabledWordbooks: List<String> = emptyList(), // 启用的词库名（顺序即优先级）；为空=全部启用
    val includeWordbooks: List<String> = emptyList()  // 强制纳入的词库名（与文件夹内 TXT 文件名匹配，忽略大小写）；不受 enabled_wordbooks 过滤
) {
    companion object { const val VOICE_CONFIG_FILE = "voice_config.txt" }
}

/** 当前生效的完整配置：主配置 + 已启用的各词库 */
class GlossarySuite(val config: GlossaryConfig, val glossaries: List<Glossary>) {
    val isUsable: Boolean
        get() = config.enabled && glossaries.isNotEmpty()
}
