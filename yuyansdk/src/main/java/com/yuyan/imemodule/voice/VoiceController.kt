package com.yuyan.imemodule.voice

import android.content.Context
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode

/**
 * 语音输入控制器。
 *
 * 职责：把 [BiDiVoiceClient]（说点啥识别）、按键交互模式（点按/长按）、状态反馈，
 * 以及“识别结果 → 上屏”串在一起。上屏入口 [onVoiceFinalText] 由 ImeService 注入；
 * 热词/正则/发音相似度替换引擎在 ImeService 的 commitVoiceResult 里套用。
 *
 * 支持两个独立入口按钮：语音识别 A（[VoiceButton.A]）与语音识别 B（[VoiceButton.B]），
 * 各自可独立设置为点按（TOGGLE：点一下开始、再点结束）或长按（HOLD：按住开始、松手结束）。
 *
 * 每个按钮的交互模式优先级：
 *   配置文件 voice_config.txt 的 interact_mode_a/interact_mode_b（[applyConfig]，null=未指定）
 *   > 应用内设置开关（AppPrefs.voice.voiceAInteractHold / voiceBInteractHold）。
 *
 * 录音状态通过 [VoiceUiState] 广播，供功能栏图标变色提示。
 *
 * 生命周期：由 ImeService 创建；键盘显示时 [bind]，隐藏时 [unbind]。
 */
class VoiceController(
    appContext: Context,
    private val onVoiceFinalText: (String) -> Unit
) {

    /** 功能栏语音按钮标识 */
    enum class VoiceButton { A, B }

    /** 按键交互模式 */
    enum class InteractMode { TOGGLE, HOLD }

    /** 供界面订阅的状态 */
    data class State(val recording: Boolean, val processing: Boolean, val message: String?)

    /** 全局单例引用：供功能栏适配器查询当前按钮模式（无需持有 ImeService） */
    companion object {
        @Volatile
        var instance: VoiceController? = null
    }

    /** 界面监听状态变化 */
    var onStateChanged: ((State) -> Unit)? = null

    /** 说点啥客户端 */
    val client = BiDiVoiceClient(appContext)

    val isRecording: Boolean get() = client.isRecording

    /** 配置文件对 A/B 按钮的交互模式覆盖；null=未指定，改用应用内设置 */
    private var configHoldA: Boolean? = null
    private var configHoldB: Boolean? = null

    init {
        instance = this
        client.listener = object : BiDiVoiceClient.Listener {
            override fun onVoiceState(state: BiDiVoiceClient.VoiceState, message: String?) {
                VoiceUiState.setRecording(state == BiDiVoiceClient.VoiceState.Recording)
                onStateChanged?.invoke(
                    State(
                        recording = state == BiDiVoiceClient.VoiceState.Recording,
                        processing = state == BiDiVoiceClient.VoiceState.Processing,
                        message = message
                    )
                )
            }

            override fun onVoiceFinal(text: String) {
                VoiceUiState.setRecording(false)
                onStateChanged?.invoke(State(false, false, "final"))
                onVoiceFinalText(text)
            }

            override fun onVoiceError(code: Int, message: String) {
                VoiceUiState.setRecording(false)
                onStateChanged?.invoke(State(false, false, "语音出错：$message"))
            }

            override fun onVoiceAmplitude(amplitude: Float) {
                // 预留：可用作波形；当前忽略
            }
        }
    }

    /** 应用配置文件里的交互模式偏好（interact_mode_a / interact_mode_b；null=未指定） */
    fun applyConfig(interactHoldA: Boolean?, interactHoldB: Boolean?) {
        configHoldA = interactHoldA
        configHoldB = interactHoldB
    }

    /** 某按钮的交互模式 */
    fun modeFor(button: VoiceButton): InteractMode {
        val hold = when (button) {
            VoiceButton.A -> configHoldA ?: AppPrefs.getInstance().voice.voiceAInteractHold.getValue()
            VoiceButton.B -> configHoldB ?: AppPrefs.getInstance().voice.voiceBInteractHold.getValue()
        }
        return if (hold) InteractMode.HOLD else InteractMode.TOGGLE
    }

    /** 根据功能栏按钮的 SkbMenuMode 换算为按钮标识 */
    fun buttonOf(mode: SkbMenuMode): VoiceButton? = when (mode) {
        SkbMenuMode.Voice -> VoiceButton.A
        SkbMenuMode.VoiceB -> VoiceButton.B
        else -> null
    }

    /** 键盘显示时调用 */
    fun bind() { client.bind() }

    /** 键盘隐藏时调用 */
    fun unbind() { client.unbind() }

    /** 功能栏「语音」按钮的普通点击（TOGGLE：点一下开始、再点结束；HOLD：点击即开始） */
    fun onClick(button: VoiceButton) {
        when (modeFor(button)) {
            InteractMode.TOGGLE -> if (client.isRecording) client.stop() else client.start()
            InteractMode.HOLD -> client.start()
        }
    }

    /** HOLD 模式：手指按下 */
    fun onButtonDown(button: VoiceButton) {
        if (modeFor(button) == InteractMode.HOLD) client.start()
    }

    /** HOLD 模式：手指抬起 */
    fun onButtonUp() { client.stop() }

    /** 取消当前会话 */
    fun cancel() { client.cancel() }
}
