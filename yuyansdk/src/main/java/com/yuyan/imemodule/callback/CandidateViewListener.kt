package com.yuyan.imemodule.callback

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode

/**
 * 候选词视图监听器接口
 * @ClassName CandidateViewListener
 */
interface CandidateViewListener {
    fun onClickChoice(choiceId: Int)  //选择候选词的处理函数
    fun onClickMore(level: Int)  // 加载更多候选词
    fun onClickMenu(skbMenuMode: SkbMenuMode)  //选择设置菜单
    fun onClickClearCandidate()  //清空候选词
    fun onClickClearClipBoard() //清空剪切板
    fun onVoiceDown(skbMenuMode: SkbMenuMode)  //语音按钮按住（HOLD 模式开始录音）
    fun onVoiceUp()  //语音按钮松手（HOLD 模式结束录音）
}
