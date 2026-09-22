package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yuyan.imemodule.R
import com.yuyan.imemodule.hotword.GlossaryStore
import splitties.dimensions.dp

/**
 * 热词库设置页：列出 Documents/YuyanVoice 下的词库文件，
 * 勾选/取消即回写 voice_config.txt 的 enabled_wordbooks。
 * 说明：配置由文件驱动；修改后点「重启程序」按钮重启本进程（IME 服务同进程），
 * 下次唤出键盘即自动加载新的替换规则。
 */
class HotwordSettingsFragment : Fragment() {

    private var building = false
    private lateinit var cbContainer: LinearLayout
    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(context).apply {
            text = getString(R.string.hotword_settings_tips)
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        })

        root.addView(Button(context).apply {
            text = getString(R.string.hotword_scan)
            setOnClickListener { refresh() }
        })

        root.addView(Button(context).apply {
            text = getString(R.string.hotword_create_default)
            setOnClickListener {
                val result = GlossaryStore.ensureDefaultSetup()
                if (result.root != null) {
                    refresh()
                } else {
                    statusText.text = getString(R.string.hotword_dir_missing)
                }
            }
        })

        root.addView(Button(context).apply {
            text = getString(R.string.hotword_restart)
            setOnClickListener { restartApp() }
        })

        cbContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(cbContainer)

        statusText = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(statusText)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        building = true
        cbContainer.removeAllViews()
        statusText.text = ""

        val root = GlossaryStore.defaultRootDir()
        if (root == null) {
            statusText.text = getString(R.string.hotword_dir_missing)
            building = false
            return
        }
        val sections = GlossaryStore.listSections()
        if (sections.isEmpty()) {
            statusText.text = getString(R.string.hotword_empty)
            building = false
            return
        }
        val enabled = GlossaryStore.currentEnabledWordbooks()
        val included = GlossaryStore.currentIncludeWordbooks()
        // 名称忽略大小写匹配（配置里写词库a 也能对上 [[词库A]]）
        fun nameHit(list: List<String>, n: String): Boolean =
            list.any { it.trim().lowercase().removeSuffix(".txt") == n.trim().lowercase().removeSuffix(".txt") }
        val allEnabled = enabled.isEmpty()
        // 同名词库跨文件出现时，显示名追加（文件名）区分
        val dupNames = sections.groupBy { it.name.trim().lowercase() }.filterValues { it.size > 1 }.keys
        fun displayName(s: GlossaryStore.SectionRef): String =
            if (s.name.trim().lowercase() in dupNames) "${s.name}（${s.file}）" else s.name

        for (s in sections) {
            cbContainer.addView(CheckBox(requireContext()).apply {
                text = displayName(s)
                tag = s.name           // 真实词库名（回写 enabled_wordbooks 用，不带显示后缀）
                isChecked = allEnabled || nameHit(enabled, s.name) || nameHit(included, s.name)
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, _ -> onWordbookToggled() }
            })
        }
        val onCount = cbContainer.childCount.let { c ->
            (0 until c).count { (cbContainer.getChildAt(it) as CheckBox).isChecked }
        }
        val includeTip = if (included.isNotEmpty()) {
            "\ninclude_wordbooks 强制纳入：${included.joinToString("、")}"
        } else {
            ""
        }
        val versionTip = "配置版本：v${GlossaryStore.currentConfigVersion()}（程序期望 v${GlossaryStore.CONFIG_VERSION}）"
        val dirTip = if (GlossaryStore.candidateRootDirs().size > 1) {
            "\n⚠ 发现多个 YuyanVoice 目录，当前使用：${root.absolutePath}\n请确认词库文件都放在这个目录里"
        } else {
            ""
        }
        statusText.text = "$versionTip\n共 ${sections.size} 个词库，已启用 $onCount 个$includeTip\n目录：${root.absolutePath}$dirTip"
        building = false
    }

    private fun onWordbookToggled() {
        if (building) return
        val checked = (0 until cbContainer.childCount)
            .map { cbContainer.getChildAt(it) as CheckBox }
            .filter { it.isChecked }
            .map { (it.tag as? String) ?: it.text.toString() }
        // 全不勾时写占位名，使引擎不加载任何词库（enabled_wordbooks 为空=全部启用，故不能留空）
        val list = if (checked.isEmpty()) listOf("_none_") else checked
        if (GlossaryStore.setEnabledWordbooks(list)) {
            statusText.text = getString(R.string.hotword_saved)
        } else {
            statusText.text = getString(R.string.hotword_dir_missing)
        }
    }

    /**
     * 重启本程序进程：设置页与 IME 服务同进程，重启后下次唤出键盘，
     * 输入法会重新读取全部配置文件（替换规则、交互方式、启用词库），立即生效。
     */
    private fun restartApp() {
        val ctx = requireContext()
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(ctx, getString(R.string.hotword_restart_failed), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, getString(R.string.hotword_restarting), Toast.LENGTH_SHORT).show()
        // 稍等片刻让系统接收重启意图，然后结束本进程，由系统拉起新进程
        Thread {
            try {
                Thread.sleep(800)
            } catch (_: InterruptedException) {
            }
            Process.killProcess(Process.myPid())
        }.start()
    }
}
