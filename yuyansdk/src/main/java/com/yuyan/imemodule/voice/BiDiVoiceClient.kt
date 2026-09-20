package com.yuyan.imemodule.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.brycewg.asrkb.aidl.IExternalSpeechService
import com.brycewg.asrkb.aidl.ISpeechCallback
import com.brycewg.asrkb.aidl.SpeechConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 说点啥「外部语音识别服务」的雨燕侧客户端。
 *
 * 用途：雨燕不自己占麦克风，而是通过说点啥对外导出的 AIDL 服务（ExternalSpeechService）
 * 里的 startSession(说点啥自己收音) 完成录音与识别，再把文字回调给雨燕上屏。
 * 与说点啥官方接入方式（小企鹅/同文 IME）一致；说点啥侧零修改。
 *
 * 说明：绑定是异步的；调用方可能在我尚未绑定时就点了开始，因此内部做“连接队列”，
 * 待服务就绪后再发实际事务；回调整齐切到主线程。线程安全。
 *
 * 前置条件：已安装「说点啥」并在其设置中打开“对外接口 / 外部联动”。
 */
class BiDiVoiceClient(private val appContext: Context) {

    interface Listener {
        /** 会话状态变化（主线程回调） */
        fun onVoiceState(state: VoiceState, message: String?)

        /** 最终识别文字（主线程回调，可直接上屏） */
        fun onVoiceFinal(text: String)

        /** 错误（主线程回调） */
        fun onVoiceError(code: Int, message: String)

        /** 实时音量 0.0~1.0（主线程回调，界面可忽略） */
        fun onVoiceAmplitude(amplitude: Float)
    }

    enum class VoiceState { Idle, Starting, Recording, Processing, Error }

    var listener: Listener? = null

    companion object {
        private const val TAG = "BiDiVoiceClient"

        const val SERVICE_PACKAGE = "com.brycewg.asrkb"
        const val SERVICE_CLASS = "com.brycewg.asrkb.api.ExternalSpeechService"

        // ISpeechCallback 状态常量（与说点啥一致）
        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1
        private const val STATE_PROCESSING = 2
        private const val STATE_ERROR = 3

        // startSession 返回的错误码（与说点啥一致）
        const val ERR_FEATURE_DISABLED = -1
        const val ERR_BUSY = -2
        const val ERR_INVALID = -3
        const val ERR_NO_PERMISSION = -4
        const val ERR_UNSUPPORTED = -5
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bound = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private val sessionId = AtomicInteger(-1)

    private var service: IExternalSpeechService? = null

    /**
     * 会话看门狗：说点啥退到后台/服务被杀时可能收不到 onFinal/onError，
     * 导致 sessionActive 卡在 true、后续点击全部被忽略（表现为“只能识别一次”）。
     * 只要一段时间内没有任何回调，就自动复位会话，让下一次点击能重新开始。
     */
    private val WATCHDOG_MS = 60_000L
    private val watchdog = Runnable {
        if (sessionActive.get()) {
            sessionActive.set(false)
            this@BiDiVoiceClient.sessionId.set(-1)
            postState(VoiceState.Idle, "会话超时，已自动复位")
        }
    }

    private fun armWatchdog() {
        cancelWatchdog()
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdog)
    }

    /** 绑定时可能已排队待执行的动作（服务连上后再补发） */
    private var pendingStart = false
    private var pendingStop = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IExternalSpeechService.Stub.asInterface(binder)
            bound.set(true)
            Log.i(TAG, "已连接说点啥外部识别服务")
            if (pendingStart) {
                pendingStart = false
                startSession()
            } else if (pendingStop) {
                pendingStop = false
                stopAsync()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound.set(false)
            service = null
            cancelWatchdog()
            if (sessionActive.get()) {
                sessionActive.set(false)
                this@BiDiVoiceClient.sessionId.set(-1)
                postState(VoiceState.Error, "说点啥服务已断开")
            }
        }
    }

    private val callback = object : ISpeechCallback.Stub() {
        override fun onState(sessionId: Int, state: Int, message: String?) {
            val s = when (state) {
                STATE_RECORDING -> VoiceState.Recording
                STATE_PROCESSING -> VoiceState.Processing
                STATE_IDLE -> VoiceState.Idle
                STATE_ERROR -> VoiceState.Error
                else -> VoiceState.Recording
            }
            if (s == VoiceState.Idle || s == VoiceState.Error) {
                sessionActive.set(false)
                this@BiDiVoiceClient.sessionId.set(-1)
                cancelWatchdog()
            } else {
                armWatchdog() // 会话活跃中：只要还有动静就顺延
            }
            postState(s, message)
        }

        override fun onPartial(sessionId: Int, text: String?) {
            armWatchdog()
            // M1 暂不展示流式中间结果；后续版本可在候选栏流式预览
        }

        override fun onFinal(sessionId: Int, text: String?) {
            sessionActive.set(false)
            this@BiDiVoiceClient.sessionId.set(-1)
            cancelWatchdog()
            if (!text.isNullOrBlank()) {
                mainHandler.post { listener?.onVoiceFinal(text) }
            }
            postState(VoiceState.Idle, "final")
        }

        override fun onError(sessionId: Int, code: Int, message: String?) {
            sessionActive.set(false)
            this@BiDiVoiceClient.sessionId.set(-1)
            cancelWatchdog()
            val msg = message ?: "识别错误($code)"
            mainHandler.post { listener?.onVoiceError(code, msg) }
            postState(VoiceState.Error, msg)
        }

        override fun onAmplitude(sessionId: Int, amplitude: Float) {
            armWatchdog()
            mainHandler.post { listener?.onVoiceAmplitude(amplitude) }
        }
    }

    /** 主动绑定到说点啥识别服务（幂等，绑定时忽略重复调用）。 */
    fun bind() {
        if (bound.get()) return
        val intent = Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "bindService failed", t)
            postError(-1000, "无法连接说点啥识别服务（请确认已安装说点啥，并在其设置中开启“对外接口”）")
        }
    }

    fun unbind() {
        cancelWatchdog()
        if (bound.compareAndSet(true, false)) {
            try { appContext.unbindService(connection) } catch (_: Throwable) {}
        }
        service = null
        sessionActive.set(false)
        sessionId.set(-1)
    }

    /** 开始/继续一次录音识别（说点啥在自己侧收音）。 */
    fun start() {
        if (service == null) {
            pendingStart = true
            bind()
            return
        }
        startSession()
    }

    private fun startSession() {
        val svc = service ?: return
        val oldSid = sessionId.get()
        if (sessionActive.get()) {
            // 上一次会话可能因说点啥退后台/被杀而没有收到结束回调：先尽力取消旧会话再开新会话，
            // 避免“只能识别一次”的卡死状态。
            if (oldSid > 0) {
                try { svc.cancelSession(oldSid) } catch (_: Throwable) {}
            }
            sessionActive.set(false)
            sessionId.set(-1)
        }
        try {
            val cfg = SpeechConfig(null, true, null, null, "yuyan_ime")
            val sid = svc.startSession(cfg, callback)
            if (sid > 0) {
                sessionActive.set(true)
                sessionId.set(sid)
                armWatchdog()
                postState(VoiceState.Starting, "started")
            } else {
                serviceError(sid)
            }
        } catch (t: Throwable) {
            // 死连接：复位绑定，下次点击自动重新绑定
            bound.set(false)
            service = null
            postError(-1000, "startSession 调用失败:${t.message}")
        }
    }

    /** 结束录音并进入识别（说点啥停止收音，产出最终结果）。 */
    fun stop() {
        if (service == null) {
            pendingStop = true
            bind()
            return
        }
        stopAsync()
    }

    private fun stopAsync() {
        val svc = service ?: return
        val sid = sessionId.get()
        if (sid > 0) {
            try { svc.stopSession(sid) } catch (_: Throwable) {}
        }
    }

    /** 取消当前会话（不期望最终结果）。 */
    fun cancel() {
        val svc = service ?: return
        val sid = sessionId.get()
        pendingStart = false
        pendingStop = false
        cancelWatchdog()
        if (sid > 0) {
            try { svc.cancelSession(sid) } catch (_: Throwable) {}
        }
        sessionActive.set(false)
        sessionId.set(-1)
        postState(VoiceState.Idle, "cancelled")
    }

    val isRecording: Boolean
        get() = sessionActive.get()

    private fun serviceError(errorCode: Int) {
        sessionActive.set(false)
        sessionId.set(-1)
        cancelWatchdog()
        val msg = when (errorCode) {
            ERR_FEATURE_DISABLED -> "说点啥未开启“对外接口”（feature disabled）"
            ERR_BUSY -> "说点啥正忙（已有识别会话进行中）"
            ERR_INVALID -> "会话参数无效"
            ERR_NO_PERMISSION -> "说点啥没有麦克风权限"
            ERR_UNSUPPORTED -> "当前引擎不支持该调用方式"
            else -> "说点啥返回错误码 ${errorCode}"
        }
        postError(errorCode, msg)
    }

    private fun postState(state: VoiceState, message: String?) {
        mainHandler.post { listener?.onVoiceState(state, message) }
    }

    private fun postError(code: Int, msg: String) {
        mainHandler.post { listener?.onVoiceError(code, msg) }
    }

    override fun toString(): String =
        "BiDiVoiceClient{bound=${bound.get()}, recording=${sessionActive.get()}}"
}