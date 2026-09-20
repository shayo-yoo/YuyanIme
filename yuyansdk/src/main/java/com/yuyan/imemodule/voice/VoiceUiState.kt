package com.yuyan.imemodule.voice

/**
 * 语音录音状态的全局可见标志：供功能栏/候选栏图标变色提示使用。
 * VoiceController 在状态变化时调用 [setRecording]；各适配器注册 [addListener] 以便刷新图标。
 * 所有 setter 调用都在主线程（BiDiVoiceClient 回调已 post 到主线程）。
 */
object VoiceUiState {
    @Volatile
    var isRecording: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** 由 VoiceController 调用（主线程） */
    fun setRecording(recording: Boolean) {
        if (isRecording != recording) {
            isRecording = recording
            listeners.toList().forEach { it() }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
}
