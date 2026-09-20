package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.yuyan.imemodule.R
import com.yuyan.imemodule.hotword.GlossaryStore
import splitties.dimensions.dp

/**
 * 热词库设置页：列出 Documents/YuyanVoice 下的词库文件，
 * 勾选/取消即回写 voice_config.txt 的 enabled_wordbooks。
 * 说明：配置由文件驱动；修改后下次启动输入法（或下次语音输入重新加载引擎）生效。
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
        val names = GlossaryStore.listWordbooks()
        if (names.isEmpty()) {
            statusText.text = getString(R.string.hotword_empty)
            building = false
            return
        }
        val enabled = GlossaryStore.currentEnabledWordbooks()
        val enabledSet = if (enabled.isEmpty()) names.toSet() else enabled.toSet()

        for (name in names) {
            cbContainer.addView(CheckBox(requireContext()).apply {
                text = name
                isChecked = enabledSet.contains(name)
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, _ -> onWordbookToggled() }
            })
        }
        statusText.text = "${root.absolutePath}"
        building = false
    }

    private fun onWordbookToggled() {
        if (building) return
        val checked = (0 until cbContainer.childCount)
            .map { cbContainer.getChildAt(it) as CheckBox }
            .filter { it.isChecked }
            .map { it.text.toString() }
        // 全不勾时写占位名，使引擎不加载任何词库（enabled_wordbooks 为空=全部启用，故不能留空）
        val list = if (checked.isEmpty()) listOf("_none_") else checked
        if (GlossaryStore.setEnabledWordbooks(list)) {
            statusText.text = getString(R.string.hotword_saved)
        } else {
            statusText.text = getString(R.string.hotword_dir_missing)
        }
    }
}
