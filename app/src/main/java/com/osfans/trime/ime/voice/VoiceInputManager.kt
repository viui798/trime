/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.ime.voice

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.osfans.trime.R
import com.osfans.trime.data.voice.JsVoiceCallbacks
import com.osfans.trime.data.voice.JsVoiceHostPermissions
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.data.voice.JsVoiceSession
import com.osfans.trime.data.voice.VoiceAudioCapture
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.voice.VoiceHostPermissionDialog
import com.osfans.trime.util.appContext
import com.osfans.trime.util.toast
import com.osfans.trime.util.withMaterialDialogTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 芝版内置语音输入的总控。
 *
 * 一次会话的生命周期：CONNECTING（建连）→ LISTENING（采集并上屏 partial）→ PROCESSING（松手后等
 * 最终结果）→ IDLE。JS 插件的 QuickJS 宿主在 [JsVoiceSession] 里跑，麦克风在 [VoiceAudioCapture]
 * 里采，本对象只做线程切换、上屏和 UI 状态广播。
 *
 * 与中州鹅的实现相比去掉了 AIDL 与外部 provider：芝版是单进程输入法，直接回调更简单，
 * 也避免 Binder 会话语义带来的额外失败面。
 */
object VoiceInputManager {

    enum class Phase { IDLE, CONNECTING, LISTENING, PROCESSING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val pluginId: String? = null,
        val partial: String = "",
        val level: Int = 0,
        /** 咩版式按住说话：全程不显示覆盖层，键盘保持原样。 */
        val hold: Boolean = false,
    ) {
        val active: Boolean get() = phase != Phase.IDLE
    }

    interface Listener {
        fun onVoiceState(
            state: State,
            previous: State,
        )

        fun onVoiceError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var current = State()
    private var generation = 0
    private var session: JsVoiceSession? = null
    private var capture: VoiceAudioCapture? = null
    private var serviceRef: WeakReference<TrimeInputMethodService>? = null
    private var watchdog: Runnable? = null
    private var pendingStart: Pair<WeakReference<TrimeInputMethodService>, String>? = null

    /** 当前会话是否为咩版式按住说话（松手自动收尾、无覆盖层）。 */
    @Volatile
    private var holdMode = false

    fun state(): State = current

    fun isBuiltIn(pluginId: String): Boolean = pluginId.startsWith("js:")

    fun addListener(listener: Listener) {
        listeners += listener
        main.post { listener.onVoiceState(current, current) }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun toggle(
        service: TrimeInputMethodService,
        pluginId: String,
    ) {
        if (current.active) {
            if (current.phase == Phase.LISTENING || current.phase == Phase.CONNECTING) stop() else cancel()
        } else {
            start(service, pluginId)
        }
    }

    /**
     * 咩版式按住说话：按下（长按触发）即开始录音，全程不显示覆盖层；
     * 手指抬起时由 [releaseHold] 收尾进识别。选择引擎、权限弹窗复用 [start] 的路径。
     */
    fun startHold(
        service: TrimeInputMethodService,
        pluginId: String,
    ) {
        if (current.active) return
        holdMode = true
        start(service, pluginId)
    }

    /** 按住的语音键抬起：停止录音，进入等待识别结果。 */
    fun releaseHold() {
        if (!holdMode) return
        holdMode = false
        if (current.phase == Phase.LISTENING || current.phase == Phase.CONNECTING) stop()
    }

    fun start(
        service: TrimeInputMethodService,
        pluginId: String,
    ) {
        if (current.active) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast(appContext.getString(R.string.voice_error_requires_m23))
            return
        }
        Timber.i("voice: start requested plugin=$pluginId")
        if (!VoiceAudioCapture.hasPermission(appContext)) {
            Timber.i("voice: RECORD_AUDIO missing, requesting")
            pendingStart = WeakReference(service) to pluginId
            VoicePermissionLauncher.launch()
            return
        }
        val spec = JsVoiceProviders.specOf(pluginId)
        if (spec == null) {
            toast(appContext.getString(R.string.voice_error_plugin_missing, pluginId))
            return
        }
        if (!JsVoiceHostPermissions.isApproved(pluginId, spec.hosts)) {
            // 征求同意不启动录音。弹窗开着时按住键可能已经松开，
            // 下一次显式按语音键再真正开始会话。
            val dialog = MaterialAlertDialogBuilder(service.withMaterialDialogTheme())
                .setTitle(R.string.voice_network_title)
                .setMessage(
                    service.getString(
                        R.string.voice_network_message,
                        spec.label,
                        spec.hosts.sorted().joinToString("\n"),
                    ),
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.voice_network_approve) { _, _ ->
                    JsVoiceHostPermissions.approve(pluginId, spec.hosts)
                    service.toast(R.string.voice_network_approved_hint)
                }
                .create()
            service.showDialog(dialog)
            return
        }
        val script = runCatching { JsVoiceProviders.readScript(spec) }.getOrElse {
            Timber.w(it, "failed to read voice plugin script")
            toast(appContext.getString(R.string.voice_error_plugin_load_failed, spec.label))
            return
        }
        serviceRef = WeakReference(service)
        val gen = ++generation
        setState(State(Phase.CONNECTING, pluginId, hold = holdMode))
        val next = JsVoiceSession(
            pluginId = pluginId,
            scriptSource = { script },
            declaredHosts = spec.hosts,
            sampleRate = spec.sampleRate,
            locale = Locale.getDefault().toLanguageTag(),
            http = JsVoiceProviders.httpClient(),
        )
        session = next
        // 先起麦克风再起 JS：插件可能在 start 回调里立刻报「没填密钥」，
        // 若此时 capture 还没赋值，cleanup 就停不掉它，麦克风会一直录下去。
        val mic = VoiceAudioCapture(
            context = appContext,
            sampleRate = spec.sampleRate,
            onPcm = { bytes, offset, len, _ -> session?.feedAudio(bytes, offset, len) },
            onLevel = { rms -> postIfCurrent(gen) { setState(current.copy(level = rms)) } },
            onError = { message -> postIfCurrent(gen) { fail(message) } },
        )
        capture = mic
        if (!mic.start()) {
            cleanup()
            setState(State())
            return
        }
        next.start(callbacks(gen))
    }

    fun stop() {
        if (!current.active) return
        if (current.phase == Phase.PROCESSING) return
        capture?.stop()
        capture = null
        setState(current.copy(phase = Phase.PROCESSING, level = 0))
        session?.endStream()
        armWatchdog(generation)
    }

    fun cancel() {
        if (!current.active && session == null) return
        cleanup()
        setState(State())
    }

    private fun finish() {
        Timber.i("voice: finished")
        cleanup()
        setState(State())
    }

    private fun fail(message: String) {
        cleanup()
        setState(State())
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    private fun cleanup() {
        generation++
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        capture?.stop()
        capture = null
        session?.cancel()
        session = null
        serviceRef = null
    }

    private fun callbacks(gen: Int): JsVoiceCallbacks = object : JsVoiceCallbacks {
        override fun onReady() = postIfCurrent(gen) {
            Timber.i("voice: ready")
            setState(current.copy(phase = Phase.LISTENING))
        }

        override fun onPartial(text: String) = postIfCurrent(gen) {
            if (text.isEmpty()) return@postIfCurrent
            setState(current.copy(partial = text))
        }

        override fun onFinal(text: String) = postIfCurrent(gen) {
            if (text.isEmpty()) return@postIfCurrent
            commit(text)
            setState(current.copy(partial = text))
        }

        override fun onError(message: String) = postIfCurrent(gen) {
            Timber.w("voice: error %s", message)
            fail(message)
        }

        override fun onEnded() = postIfCurrent(gen) {
            finish()
        }
    }

    private fun commit(text: String) {
        val service = serviceRef?.get() ?: return
        if (service.currentInputConnection == null) {
            toast(appContext.getString(R.string.voice_error_no_input_target))
            return
        }
        service.postRimeJob {
            clearComposition()
            main.post {
                service.commitText(text)
            }
        }
    }

    private fun setState(next: State) {
        val previous = current
        current = next
        listeners.forEach { it.onVoiceState(next, previous) }
    }

    private fun postIfCurrent(
        gen: Int,
        block: () -> Unit,
    ) {
        main.post {
            if (gen == generation) block()
        }
    }

    private fun armWatchdog(gen: Int) {
        watchdog?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            if (gen != generation) return@Runnable
            fail(appContext.getString(R.string.voice_error_timeout))
        }
        watchdog = runnable
        main.postDelayed(runnable, JsVoiceSession.FINISH_WATCHDOG_MS)
    }

    fun onPermissionResult(granted: Boolean) {
        val pending = pendingStart
        pendingStart = null
        if (!granted) {
            toast(appContext.getString(R.string.voice_error_no_permission))
            return
        }
        val service = pending?.first?.get() ?: return
        start(service, pending.second)
    }

    fun onServiceDestroyed(service: TrimeInputMethodService) {
        if (serviceRef?.get() !== service) return
        cleanup()
        setState(State())
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }
}

/** 申请录音权限的透明 Activity 只负责把结果交回 [VoiceInputManager]。 */
internal object VoicePermissionLauncher {
    fun launch() {
        val intent = Intent(appContext, VoicePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        appContext.startActivity(intent)
    }
}
