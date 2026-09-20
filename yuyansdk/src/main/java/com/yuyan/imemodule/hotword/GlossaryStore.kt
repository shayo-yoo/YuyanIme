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

    /** 解析出的默认词库根目录；找不到或过不了就返回 null */
    fun defaultRootDir(): File? {
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
        return candidates.firstOrNull { it.exists() && it.isDirectory() }
    }

    /** 从指定目录加载整套配置与词库；无配置/未开启/目录无效返回 null */
    fun loadSuite(root: File): GlossarySuite? {
        val cfgFile = File(root, GlossaryConfig.VOICE_CONFIG_FILE)
        if (!cfgFile.exists() || !cfgFile.isFile) return null
        val cfg = GlossaryParser.parseConfig(cfgFile.readText())
        if (!cfg.enabled) return null

        val files = root.listFiles { f: File ->
            f.isFile && f.extension.equals("txt", ignoreCase = true) &&
                !f.name.equals(GlossaryConfig.VOICE_CONFIG_FILE, ignoreCase = true)
        } ?: return GlossarySuite(cfg, emptyList())

        val enabled = cfg.enabledWordbooks
        val chosen = if (enabled.isEmpty()) {
            files.sortedBy { it.name }
        } else {
            files.filter { enabled.contains(it.nameWithoutExtension) }
                .sortedBy { enabled.indexOf(it.nameWithoutExtension).let(::ifMinus1) }
        }

        val glossaries = chosen.map { GlossaryParser.parseGlossary(it.nameWithoutExtension, it.readText()) }
        return GlossarySuite(cfg, glossaries)
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

    /** 列出词库根目录下全部词库文件名（不含主配置），按名称排序 */
    fun listWordbooks(): List<String> {
        val root = defaultRootDir() ?: return emptyList()
        return root.listFiles { f: File ->
            f.isFile && f.extension.equals("txt", ignoreCase = true) &&
                !f.name.equals(GlossaryConfig.VOICE_CONFIG_FILE, ignoreCase = true)
        }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
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

    private fun ifMinus1(i: Int): Int = i.takeIf { it >= 0 } ?: Int.MAX_VALUE

    const val DIR_NAME = "YuyanVoice"
}