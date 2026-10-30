// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.core.whenReady
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.DeployNotification
import com.osfans.trime.util.appContext
import com.osfans.trime.util.readText
import com.osfans.trime.util.subprocess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manage the singleton instance of [Rime]
 *
 * To use rime, client should call [createSession] to obtain a [RimeSession],
 * and call [destroySession] on client destroyed. Client should not leak the instance of [RimeApi],
 * and must use [RimeSession] to access rime functionalities.
 *
 * The instance of [Rime] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Rime.finalize] will be called.
 *
 * Functions are thread-safe in this class.
 *
 * Adapted from [fcitx5-android/FcitxDaemon.kt](https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)
 */
object RimeDaemon {

    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override fun <T> peek(block: RimeApi.() -> T): T = ensureEstablished {
            block(rimeImpl)
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch {
                        block(rimeImpl)
                    }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            // 旧 session 还挂着，但引擎可能已经停摆（见 ensureRunning 注释）。
            // 这里补一次启动尝试，避免复用到一个永远不 ready 的 session。
            ensureRunningLocked()
            return@withLock sessions.getValue(name)
        }
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
            Timber.d("RimeDaemon start rime")
            realRime.startup()
        }
        val session = establish(name)
        sessions[name] = session
        return@withLock session
    }

    /**
     * 芝版同文：引擎自愈。
     *
     * 引擎启动前会检查存储是否可用（`Rime.startup()`）。外部目录权限被系统撤销
     * （典型场景：卸载重装后「所有文件访问」授权被重置）、或存储向导没走完导致
     * 引擎根为空时，这次启动会被**直接跳过**，而 [createSession] 对已存在的
     * session 是幂等的，不会重试启动。
     *
     * 结果就是：键盘能弹出来，但所有按键都排在 [RimeSession.runOnReady] 上永久
     * 挂起、主题也永远不初始化，界面一片空白 / 完全没响应，只能靠手动「部署」
     * （绕过存储检查直接拉起 JNI）才能恢复。
     *
     * 这个方法给出一条自愈路径：每次弹出输入法时，只要引擎还停在 STOPPED 就
     * 再试一次启动。权限恢复后用户重新点一下输入框即可恢复，不必重启应用或部署。
     *
     * @return 引擎当前是否可用（启动中视为可用，让调用方照常排队等待）。
     */
    fun ensureRunning(): Boolean = lock.withLock { ensureRunningLocked() }

    /** 引擎唤醒失败后的重试间隔，避免按键连发时反复尝试刷日志。 */
    private const val STARTUP_RETRY_INTERVAL_MS = 2000L

    @Volatile
    private var lastStartupAttempt = 0L

    private fun ensureRunningLocked(): Boolean {
        val state = realRime.lifecycle.currentState
        if (state != RimeLifecycle.State.STOPPED) return true
        val now = System.currentTimeMillis()
        if (now - lastStartupAttempt < STARTUP_RETRY_INTERVAL_MS) return false
        lastStartupAttempt = now
        Timber.w("RimeDaemon: engine is stopped, retrying startup")
        val started = realRime.startup()
        if (!started) {
            Timber.w("RimeDaemon: startup refused again, storage still unavailable")
        }
        return started
    }

    fun destroySession(name: String): Unit = lock.withLock {
        if (name !in sessions) {
            return
        }
        sessions -= name
        if (sessions.isEmpty()) {
            Timber.d("RimeDaemon stop rime")
            realRime.finalize()
        }
    }

    /**
     * Reuse a session for remote service
     */
    fun getFirstSessionOrNull() = sessions.firstNotNullOfOrNull { it.value }

    private var restartId = 0

    /** Directory changes happen only after native workers and sessions have stopped. */
    fun changeStorage(block: () -> Unit) = lock.withLock {
        check(realRime.lifecycle.currentState == com.osfans.trime.core.RimeLifecycle.State.READY ||
            realRime.lifecycle.currentState == com.osfans.trime.core.RimeLifecycle.State.STOPPED) {
            "引擎正在启动或部署，请完成后再切换目录"
        }
        realRime.finalize()
        try {
            block()
        } finally {
            realRime.startup()
        }
    }

    init {
        DeployNotification.ensureChannel()
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.messageFlow.collect {
                handleRimeMessage(it)
            }
        }
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder =
            NotificationCompat
                .Builder(appContext, DeployNotification.CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.rime_daemon))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) = lock.withLock {
        val id = restartId++
        if (!fullCheck) {
            sendNotification(id) {
                setSmallIcon(R.drawable.ic_baseline_sync_24)
                setContentTitle(appContext.getString(R.string.rime_daemon))
                setContentText(appContext.getString(R.string.restarting_rime))
                setOngoing(true)
                setProgress(100, 0, true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }
        realRime.finalize()
        realRime.startup()
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.lifecycle.whenReady {
                notificationManager.cancel(id)
            }
        }
    }

    fun stopRime() {
        realRime.finalize()
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it is RimeMessage.DeployMessage) {
            when (it.data) {
                RimeMessage.DeployMessage.State.Start -> {
                    DeployNotification.showProgress()
                    withContext(Dispatchers.IO) { subprocess("logcat", "--clear") }
                }

                RimeMessage.DeployMessage.State.Success -> {
                    DeployNotification.showSuccess()
                }

                RimeMessage.DeployMessage.State.Failure -> {
                    val intent =
                        Intent(appContext, LogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            val log =
                                subprocess("logcat", "-v", "brief", "-s", "rime.trime:W", "-d")
                                    .readText()
                            putExtra(LogActivity.FROM_DEPLOY, true)
                            putExtra(LogActivity.DEPLOY_FAILURE_TRACE, log)
                        }
                    val pendingIntent =
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    DeployNotification.showFailure(pendingIntent)
                }
            }
        }
    }
}
