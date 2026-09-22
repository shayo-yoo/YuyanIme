package com.yuyan.imemodule.hotword

import android.os.Environment
import java.io.File

/**
 * 词库目录读写。
 *
 * 存放目录（推荐）：手机公共文档目录 / Documents/YuyanVoice/
 *   ├─ voice_config.txt        主配置
 *   └─ wordbook_*.txt          各词库
 *
 * 读取使用 `loadSuite(rootDir)`（纯逻辑，可脱离手机测试）；目录解析用 [defaultRootDir]。
 */
object GlossaryStore {

    /** 当前程序期望的主配置格式版本（voice_config.txt 的 config_version 字段） */
    const val CONFIG_VERSION = 3

    /** ensureDefaultSetup 的返回结果 */
    data class SetupResult(val root: File?, val configRebuilt: Boolean)

    /** 解析出的默认词库根目录；找不到或过不了就返回 null */
    fun defaultRootDir(): File? = candidateRootDirs().firstOrNull()

    /** 所有“可能是词库目录”的候选目录（按优先级排序，仅返回存在的目录） */
    fun candidateRootDirs(): List<File> {
        val candidates = mutableListOf<File>()
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                ?.let { candidates.add(File(it, DIR_NAME)) }
        } catch (_: Throwable) {
        }
        try {
            Environment.getExternalStorageDirectory()?.let {
                candidates.add(File(it, "Documents/$DIR_NAME"))
                candidates.add(File(it, DIR_NAME))
            }
        } catch (_: Throwable) {
        }
        return candidates.filter { it.exists() && it.isDirectory() }.distinctBy { it.absolutePath }
    }

    /** 判定一个文件是否为词库文件：.txt 或无后缀（兼容手机文件管理器建出的无后缀文件），排除主配置/备份/说明 */
    private fun isWordbookFile(f: File): Boolean {
        if (!f.isFile) return false
        val name = f.name
        if (name.equals(GlossaryConfig.VOICE_CONFIG_FILE, ignoreCase = true)) return false
        if (name.endsWith(".bak", ignoreCase = true)) return false
        val ext = f.extension
        return ext.isEmpty() || ext.equals("txt", ignoreCase = true)
    }

    /**
     * 确保词库目录与默认文件存在（不存在则创建）。
     * 首次使用时调用：自动建 Documents/YuyanVoice，并写入主配置 voice_config.txt
     * 与示例词库 wordbook_default.txt，用户即可直接开始配置。
     * 若已有配置但其 config_version 低于程序期望版本，则把旧配置备份为
     * voice_config.v{N}.bak，再写入本版本默认配置（configRebuilt=true），
     * 调用方可据此提示用户到热词库设置页重新勾选生效词库。
     */
    fun ensureDefaultSetup(): SetupResult {
        val root = defaultRootDir() ?: run {
            val docs = try {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            } catch (_: Throwable) { null }
            val target = docs?.let { File(it, DIR_NAME) }
            if (target == null || (!target.exists() && !target.mkdirs())) return SetupResult(null, false)
            target
        }
        return try {
            var rebuilt = false
            val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
            if (cfgFile.isFile) {
                val oldVersion = try {
                    GlossaryParser.parseConfig(cfgFile.readText()).configVersion
                } catch (t: Throwable) {
                    0
                }
                if (oldVersion < CONFIG_VERSION) {
                    val bak = File(root, "voice_config.v$oldVersion.bak")
                    if (bak.exists()) bak.delete()
                    cfgFile.copyTo(bak)
                    cfgFile.writeText(DEFAULT_CONFIG_TEXT)
                    rebuilt = true
                }
            } else {
                cfgFile.writeText(DEFAULT_CONFIG_TEXT)
            }
            val sample = File(root, "wordbook_default.txt")
            if (!sample.isFile) {
                sample.writeText(DEFAULT_WORDBOOK_TEXT)
            }
            val readme = File(root, "README.md")
            if (!readme.isFile || rebuilt) {
                readme.writeText(DEFAULT_README_TEXT)
            }
            SetupResult(root, rebuilt)
        } catch (t: Throwable) {
            SetupResult(null, false)
        }
    }

    private val DEFAULT_CONFIG_TEXT: String
        get() = listOf(
            "# YuyanVoice 语音热词库主配置（自动创建）",
            "# 词库文件放到本目录：Documents/YuyanVoice/",
            "config_version = $CONFIG_VERSION",
            "# 版本低于程序期望时会自动备份旧配置并重建本文件（旧配置存为 voice_config.v旧版本号.bak）",
            "enable_voice = true",
            "# 交互：tap=点按  hold=按住录音（interact_mode_a/b 可分别作用于语音识别A/B）",
            "interact_mode = tap",
            "similarity_threshold = 0.8",
            "apply_order = regex,hotword",
            "enabled_wordbooks = 默认词库,技术词库",
            "# 强制纳入的词库：[[词库名]] 的名字，忽略大小写；不受上方过滤；留空=不使用",
            "include_wordbooks = "
        ).joinToString("\n") + "\n"

    private val DEFAULT_WORDBOOK_TEXT: String
        get() = listOf(
            "# 示例词库 wordbook_default.txt（自动创建）",
            "# 用 [[词库名]] 在同一个文件里划分多个词库（名称随意，被 [[ ]] 包裹即可）",
            "# 含 = 的行 = 旧式兼容写法：pattern = 替换结果（支持标准正则）；替换内容含 | → 近音热词",
            "# 无 = 的行 = 手册新语法热词：目标词 | 匹配词1 | 匹配词2 [~~~ 局部黑名单] [! 全文黑名单] [+ 全文白名单] [条件词:替换词]",
            "",
            "[[默认词库]]",
            "# 正则/精确替换",
            "毫安时 = mAh",
            "二零二四 = 2024",
            "# 手册新语法热词：目标词 | 匹配词（命中任意一个 → 替换为目标词）",
            "回龙观 | 回笼灌 | 回龙灌 | 慧龙观",
            "语音识别 | 语音试别 | 语音设别",
            "Claude | 克劳德 | 克劳得 | cloud ! 天气|气象 + 对话|聊天 [翻译:翻译版克劳德]",
            "",
            "[[技术词库]]",
            "负一 = -1",
            "([0-9]+)点([0-9]+) = ${'$'}1.${'$'}2"
        ).joinToString("\n") + "\n"

    private val DEFAULT_README_TEXT: String
        get() = listOf(
            "# YuyanVoice 语音热词库 —— 配置说明",
            "",
            "本目录存放雨燕输入法语音识别的替换规则。所有文件均为普通文本（UTF-8 编码），",
            "用手机文件管理器或电脑记事本即可编辑。",
            "",
            "## 目录结构",
            "",
            "Documents/YuyanVoice/",
            "├── README.md              本说明文件",
            "├── voice_config.txt       主配置（开关、交互方式、相似度阈值、启用词库）",
            "└── wordbook_*.txt         词库文件（用 [[词库名]] 在一个文件里划分多个词库）",
            "",
            "## 文件一：voice_config.txt（主配置）",
            "",
            "| 配置项 | 取值 | 说明 |",
            "|---|---|---|",
            "| config_version | 数字 | 配置格式版本。低于程序期望版本时自动备份旧配置（voice_config.v旧版本号.bak）并重建本文件 |",
            "| enable_voice | true / false | 总开关，false 时语音按钮不识别 |",
            "| interact_mode | tap / hold | 默认交互方式：tap=点按开始、再点结束；hold=按住录音、松开结束 |",
            "| interact_mode_a | tap / hold | 只影响「语音识别A」按钮 |",
            "| interact_mode_b | tap / hold | 只影响「语音识别B」按钮 |",
            "| similarity_threshold | 0~1 小数 | 热词相似度阈值：0.8 表示与目标音相似度≥0.8 才替换；越低越宽松 |",
            "| apply_order | regex,hotword | 应用顺序：先做正则替换，再做热词替换（可写 hotword,regex 反过来） |",
            "| enabled_wordbooks | 词库名,词库名 | 启用的词库列表（[[词库名]] 里的名字）。留空=全部启用；全部停用写 _none_ |",
            "| include_wordbooks | 词库名,词库名 | 强制纳入的列表（同上，忽略大小写），不受上方 enabled 过滤；留空=不使用 |",
            "",
            "示例：",
            "config_version = 3",
            "enable_voice = true",
            "interact_mode = tap",
            "similarity_threshold = 0.8",
            "apply_order = regex,hotword",
            "enabled_wordbooks = 默认词库,技术词库",
            "include_wordbooks = 词库A,词库B",
            "",
            "> 新加的词库为什么没生效？请检查：① 文件名最好是 .txt 结尾（没有后缀也能识别，但建议加上）；",
            "> ② 若 enabled_wordbooks 非空，新词库需在设置页勾选，或在 include_wordbooks 里写上名字；",
            "> ③ 改完配置后到设置页点「重启程序」。",
            "",
            "## 文件二：词库文件（wordbook_*.txt，推荐单文件多词库）",
            "",
            "在同一个文件里用 `[[词库名]]` 划分多个词库（名称随意，只要被 [[ ]] 包裹；可多个文件、每个文件多个词库）。",
            "分段逻辑从上到下：词库一 与 词库二 之间的规则属于 词库一；词库二 与 词库三 之间属于 词库二；",
            "末尾的规则属于最后一个词库；第一个 `[[词库名]]` 之前的规则也属于第一个词库。",
            "",
            "示例：",
            "[[默认词库]]",
            "毫安时 = mAh",
            "二零二四 = 2024",
            "Claude | 克劳德 | 克劳得 | cloud",
            "",
            "[[技术词库]]",
            "负一 = -1",
            "([0-9]+)点([0-9]+) = ${'$'}1.${'$'}2",
            "",
            "### 热词行语法（手册新语法，无等号）",
            "",
            "格式：`目标词 | 匹配词1 | 匹配词2 [~~~ 局部黑名单] [! 全文黑名单] [+ 全文白名单] [条件词X:替换词A1]`",
            "",
            "| 段 | 写法 | 效果 |",
            "|---|---|---|",
            "| 主表达式 | `目标词 \\| 匹配词1 \\| 匹配词2` | 第一个是目标词（默认替换结果），其余是匹配词，命中任意一个即触发 |",
            "| 局部黑名单 | `~~~ 词1\\|词2` | 命中位置**附近**（左右各 5 个字符）出现任一词 → 该处不替换 |",
            "| 全文黑名单 | `! 词1\\|词2` | 整句（任意位置）出现任一词 → 本规则整体失效（优先级最高） |",
            "| 全文白名单 | `+ 词1\\|词2` | 整句必须出现至少一个词，否则本规则整体失效 |",
            "| 条件块 | `[条件1\\|条件2:替换词]` | 整句包含条件词（| 为 OR）→ 本次替换结果改用替换词；首个命中的块生效 |",
            "",
            "示例：",
            "Claude | 克劳德 | 克劳得 | cloud ~~~ weather          # 匹配位置附近出现 weather 时不替换",
            "启动器 | 打开应用 ! 天气|气象 + 对话|聊天               # 整句含天气/气象→不替换；必须含对话或聊天",
            "游戏启动器 | 打开游戏 [手游|手机:手游版启动器] [端游:端游版启动器]  # 含手游/手机→替换成“手游版启动器”，含端游→“端游版启动器”",
            "",
            "### 旧式写法（含 =，兼容原版，行为不变）",
            "",
            "- `pattern = 替换结果`：正则/精确替换（支持标准正则表达式；正则写错该行会被跳过）。",
            "- `规范词 = 别名1 | 别名2`：近音热词，说出的声音与别名相似度 ≥ similarity_threshold 时替换成规范词。",
            "- 行尾修饰符 `!黑名单 +白名单` 与旧式 `~~~ 黑名单`（整句包含即不替换）继续支持。",
            "",
            "### 每个词库独立开关",
            "",
            "设置 → 热词库设置页会把每个 `[[词库名]]` 列成一行，可逐个勾选；",
            "不勾选的词库，其中的所有词汇在替换时都会被跳过（替换前会检查该词库是否开启）。",
            "",
            "## 判定细节",
            "",
            "- 黑/白名单与条件判断均基于**整句原始识别文本**、**大小写不敏感**（替换结果保持书写原样）；",
            "- 匹配无顺序性：黑/白名单词汇无论出现在句子的哪个位置都算命中；",
            "- 全文黑名单优先级 > 全文白名单：两者都命中时按黑名单失效处理；",
            "- 同一规则多处命中互不阻塞（区间不重叠即可）；不同规则的命中冲突时，分数/长度占优者胜出，区间不重叠；",
            "- 局部黑名单逐命中判定（每处独立），全文黑/白名单整规则判定。",
            "",
            "## 修改后如何生效",
            "",
            "1. 修改文件（或勾选/取消启用词库）后，必须重启雨燕输入法才会加载新规则；",
            "2. 最快方式：输入法设置 → 热词库设置页 → 点「重启程序」按钮；",
            "3. 或到系统设置里停用再启用雨燕输入法；",
            "4. 语音识别 A/B 的开关与交互方式在配置读取后也一并生效，无需其它操作。",
            "",
            "## 注意事项",
            "",
            "- 请用 UTF-8 编码保存文件（手机文件管理器自带编辑器一般默认 UTF-8，电脑上别用记事本另存为 ANSI）；",
            "- 一行只能一条规则，# 开头的行是注释；",
            "- 词库文件数量不影响速度，但建议只保留常用词库。"
        ).joinToString("\n") + "\n"

    /**
     * 从指定目录加载整套配置与词库；无配置/未开启/目录无效返回 null。
     * 词库 = 各词库文件里 `[[词库名]]` 段（旧式无标记文件整文件算一个词库）。
     */
    fun loadSuite(root: File): GlossarySuite? {
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.exists() || !cfgFile.isFile) return null
        val cfg = GlossaryParser.parseConfig(cfgFile.readText())
        if (!cfg.enabled) return null

        val files = root.listFiles { f: File -> isWordbookFile(f) }
            ?.sortedBy { it.name }
            ?: return GlossarySuite(cfg, emptyList())

        val all = files.flatMap { f ->
            try {
                GlossaryParser.parseGlossary(f.nameWithoutExtension, f.readText())
            } catch (t: Throwable) {
                emptyList()
            }
        }

        // 名称规范化：忽略大小写、去掉末尾 .txt 后缀
        fun norm(s: String): String {
            var n = s.trim().lowercase()
            if (n.endsWith(".txt")) n = n.dropLast(4)
            return n
        }

        val chosen: List<Glossary> = if (cfg.enabledWordbooks.isEmpty()) {
            // 未配置过滤：全部词库启用
            all
        } else {
            val lowerEnabled = cfg.enabledWordbooks.map(::norm).toSet()
            val lowerIncludes = cfg.includeWordbooks.map(::norm).toSet()
            all.filter { norm(it.name) in lowerEnabled || norm(it.name) in lowerIncludes }
        }

        return GlossarySuite(cfg, chosen)
    }

    /** 读取主配置里指定的交互模式（A/B 各自 null=未指定，用应用内设置） */
    fun voiceInteractModes(): Pair<Boolean?, Boolean?> {
        val root = defaultRootDir() ?: return null to null
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.isFile) return null to null
        return try {
            val cfg = GlossaryParser.parseConfig(cfgFile.readText())
            cfg.interactHoldA to cfg.interactHoldB
        } catch (t: Throwable) {
            null to null
        }
    }

    /** 一个词库的引用：词库名 + 来源文件名（设置页展示用） */
    data class SectionRef(val name: String, val file: String)

    /** 列出全部词库（各文件里的 `[[词库名]]` 段；旧式无标记文件整文件算一个词库），按文件名+段序 */
    fun listSections(): List<SectionRef> {
        val root = defaultRootDir() ?: return emptyList()
        return root.listFiles { f: File -> isWordbookFile(f) }
            ?.sortedBy { it.name }
            ?.flatMap { f ->
                try {
                    GlossaryParser.parseGlossary(f.nameWithoutExtension, f.readText())
                        .filter { it.regexRules.isNotEmpty() || it.hotwords.isNotEmpty() }
                        .map { SectionRef(it.name, f.nameWithoutExtension) }
                } catch (t: Throwable) {
                    emptyList()
                }
            }
            ?: emptyList()
    }

    /** 当前主配置里的 config_version（读取失败/不存在返回 0） */
    fun currentConfigVersion(): Int {
        val root = defaultRootDir() ?: return 0
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.isFile) return 0
        return try {
            GlossaryParser.parseConfig(cfgFile.readText()).configVersion
        } catch (t: Throwable) {
            0
        }
    }

    /** 当前启用的词库名（来自主配置 enabled_wordbooks；为空则视为全部） */
    fun currentEnabledWordbooks(): List<String> {
        val root = defaultRootDir() ?: return emptyList()
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.isFile) return emptyList()
        return try {
            GlossaryParser.parseConfig(cfgFile.readText()).enabledWordbooks
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 当前强制纳入的词库名（来自主配置 include_wordbooks） */
    fun currentIncludeWordbooks(): List<String> {
        val root = defaultRootDir() ?: return emptyList()
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.isFile) return emptyList()
        return try {
            GlossaryParser.parseConfig(cfgFile.readText()).includeWordbooks
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 回写主配置的 enabled_wordbooks（热词库设置页逐库开关用）。
     * 保留文件中其它行与注释，仅替换/追加该键；文件不存在则新建基础配置。
     * @return 是否成功
     */
    fun setEnabledWordbooks(enabled: List<String>): Boolean {
        val root = defaultRootDir() ?: return false
        if (!root.isDirectory && !root.mkdirs()) return false
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        val newLine = "enabled_wordbooks = ${enabled.joinToString(",")}"
        return try {
            if (!cfgFile.isFile) {
                val base = listOf(
                    "# YuyanVoice 语音热词库主配置",
                    "enable_voice = true",
                    "interact_mode = tap",
                    "similarity_threshold = 0.8",
                    "apply_order = regex,hotword",
                    newLine
                )
                cfgFile.writeText(base.joinToString("\n") + "\n")
            } else {
                val lines = cfgFile.readLines().toMutableList()
                val keyIdx = lines.indexOfFirst {
                    it.trim().lowercase().startsWith("enabled_wordbooks")
                }
                if (keyIdx >= 0) {
                    lines[keyIdx] = newLine
                } else {
                    lines.add(newLine)
                }
                cfgFile.writeText(lines.joinToString("\n") + "\n")
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    const val DIR_NAME = "YuyanVoice"
}