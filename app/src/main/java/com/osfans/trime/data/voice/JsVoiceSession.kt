/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.osfans.trime.R
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID

/** Commands queued to the per-session worker. Shared by the provider and the JS net bridge. */
internal sealed interface JsVoiceCommand {
    class Audio(val base64: String) : JsVoiceCommand

    object Stop : JsVoiceCommand

    /** 一帧服务器消息。[binaryBase64] 非空表示二进制帧（火山引擎 bigmodel 就是二进制协议）。 */
    class Message(
        val socket: String,
        val text: String,
        val binaryBase64: String? = null,
    ) : JsVoiceCommand

    class Fail(val message: String) : JsVoiceCommand
}

/** 一次识别会话对外的回调；全部可能从 IO 线程发出，调用方负责切主线程。 */
internal interface JsVoiceCallbacks {
    fun onReady()

    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onError(message: String)

    fun onEnded()
}

/**
 * Runs a Foxy-style JavaScript voice.asr plugin in QuickJS.
 *
 * 一次会话一个 QuickJS 实例 + 一个消费 [JsVoiceCommand] 的 worker，所有 JS 调用按到达顺序串行；
 * QuickJS 只在 IO 线程上求值，不碰主线程。这与 fcitx5-android 的 JsVoiceInputProvider 同构，
 * 只是去掉了 AIDL：芝版是单进程输入法，会话直接回调给 [VoiceInputManager]。
 */
internal class JsVoiceSession(
    private val pluginId: String,
    private val scriptSource: () -> String,
    private val declaredHosts: Set<String>,
    /** 插件在 feature 里声明的采样率；宿主采集端按这个值启动。 */
    val sampleRate: Int = 16000,
    private val locale: String,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var commands: Channel<JsVoiceCommand>? = null

    fun start(callbacks: JsVoiceCallbacks) {
        cancel()
        val rx = Channel<JsVoiceCommand>(Channel.UNLIMITED)
        commands = rx
        worker = scope.launch { runSession(callbacks, rx) }
    }

    fun feedAudio(
        pcm: ByteArray,
        offset: Int,
        len: Int,
    ) {
        if (offset < 0 || len <= 0 || offset + len > pcm.size) return
        commands?.trySend(JsVoiceCommand.Audio(Base64.encodeToString(pcm, offset, len, Base64.NO_WRAP)))
    }

    fun endStream() {
        commands?.trySend(JsVoiceCommand.Stop)
    }

    fun cancel() {
        worker?.cancel()
        worker = null
        commands?.close()
        commands = null
    }

    private suspend fun runSession(
        cb: JsVoiceCallbacks,
        rx: Channel<JsVoiceCommand>,
    ) {
        var notifyEnded = true
        val policy = JsVoiceNetworkPolicy(pluginId, declaredHosts)
        val net = JsVoiceNet(http, policy, rx) { field ->
            JsVoiceSettingsStore.secret(pluginId, field)
        }
        val js = QuickJs.create(Dispatchers.IO)
        try {
            // 导入的脚本可能在 register()/start() 之前就在顶层调用 native。
            policy.requireApproval()
            net.install(js, locale)
            js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + scriptSource())
            val undeclared = undeclaredHosts(js)
            if (undeclared.isNotEmpty()) {
                cb.onError(
                    appContext.getString(R.string.voice_error_undeclared_host, undeclared.joinToString(", ")),
                )
                return
            }
            val fields = describeFields(js, locale)
            val values = JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(pluginId))
            // 只要求当前可见的密钥。豆包插件按 authMode 在 apiKey 与 accessToken 之间二选一，
            // 若把 hide 着的那一个也算成必填，用户永远启动不了会话。
            val missing = fields.firstOrNull {
                it.isSecret && JsVoiceFields.isVisible(it, values) && !JsVoiceSettingsStore.hasSecret(pluginId, it.key)
            }
            if (missing != null) {
                cb.onError(
                    appContext.getString(
                        R.string.voice_error_secret_missing,
                        missing.label.ifBlank { missing.key },
                    ),
                )
                return
            }
            val settings = buildJsonObject {
                values.forEach { (key, value) -> put(key, value) }
                // 数字而不是字符串：脚本把它原样塞进 DashScope 的 parameters.sample_rate，
                // 发成 "16000" 会被服务端当成非法参数。
                put("sampleRate", JsonPrimitive(sampleRate))
                put("taskId", UUID.randomUUID().toString())
            }
            val started = call(js, "start", settings, START_TIMEOUT_MS)
            val socket = (started.result as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (handle(cb, started.actions)) return
            for (cmd in rx) {
                val finished = when (cmd) {
                    is JsVoiceCommand.Audio -> handle(
                        cb,
                        call(
                            js,
                            "audio",
                            buildJsonObject {
                                settings.forEach { (key, value) -> put(key, value) }
                                put("pcm", cmd.base64)
                                put("socket", socket)
                            },
                            AUDIO_TIMEOUT_MS,
                        ).actions,
                    )

                    JsVoiceCommand.Stop -> handle(
                        cb,
                        call(
                            js,
                            "stop",
                            buildJsonObject {
                                settings.forEach { (key, value) -> put(key, value) }
                                put("socket", socket)
                            },
                            STOP_TIMEOUT_MS,
                        ).actions,
                    )

                    is JsVoiceCommand.Message -> handle(
                        cb,
                        call(
                            js,
                            "onMessage",
                            buildJsonObject {
                                put("socket", cmd.socket)
                                // 两个键分开给：插件按 message.message 读文本帧、按 binaryBase64 读二进制帧，
                                // 混在一起会让文本协议误把 base64 当 JSON 去 parse。
                                if (cmd.binaryBase64 != null) put("binaryBase64", cmd.binaryBase64) else put("message", cmd.text)
                            },
                            MESSAGE_TIMEOUT_MS,
                        ).actions,
                    )

                    is JsVoiceCommand.Fail -> {
                        cb.onError(cmd.message)
                        true
                    }
                }
                if (finished) break
            }
        } catch (e: CancellationException) {
            notifyEnded = false
            throw e
        } catch (e: Exception) {
            cb.onError(e.message ?: "JS voice plugin failed")
        } finally {
            net.closeAll()
            runCatching { js.close() }
            if (notifyEnded) cb.onEnded()
        }
    }

    private class CallResult(
        val result: JsonElement,
        val actions: List<JsonObject>,
    )

    private suspend fun call(
        js: QuickJs,
        name: String,
        arg: JsonObject,
        timeoutMs: Long,
    ): CallResult {
        val outcome = JsVoiceInvoker.call({ code -> js.evaluate<String>(code) }, name, arg.toString(), timeoutMs)
        outcome.error?.let { error(it) }
        val actions = outcome.actions.toMutableList()
        (outcome.result as? JsonObject)?.let { actions += it }
        return CallResult(outcome.result ?: JsonNull, actions)
    }

    /** Hosts the script declared in `register()` that the bundled allowlist does not cover. */
    private suspend fun undeclaredHosts(js: QuickJs): List<String> = kotlinx.serialization.json.Json
        .parseToJsonElement(js.evaluate<String>("__hosts()"))
        .jsonArray
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filterNot { it in declaredHosts }

    /** Reads the settings the plugin declared in `configure(ui, locale)`. */
    private suspend fun describeFields(
        js: QuickJs,
        locale: String,
    ): List<JsVoiceField> {
        val raw = js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")")
        return JsVoiceFields.parse(raw)
    }

    /** Maps action objects to callbacks. Returns true when the session is over. */
    private fun handle(
        cb: JsVoiceCallbacks,
        actions: List<JsonObject>,
    ): Boolean {
        var ended = false
        for (action in actions) {
            if (action["ready"]?.jsonPrimitive?.booleanOrNull == true) cb.onReady()
            action["partial"]?.jsonPrimitive?.contentOrNull?.let { cb.onPartial(it) }
            action["final"]?.jsonPrimitive?.contentOrNull?.let { cb.onFinal(it) }
            action["error"]?.jsonPrimitive?.contentOrNull?.let {
                cb.onError(it)
                return true
            }
            if (action["ended"]?.jsonPrimitive?.booleanOrNull == true) ended = true
        }
        return ended
    }

    companion object {
        /**
         * 收尾看门狗预算：flash 模式把整段音频 POST 到云端再取回文本，Foxy 给 stop 回调的预算
         * 就是 35s；按 3s 收尾会在识别结果回来之前拆掉会话。
         */
        const val FINISH_WATCHDOG_MS = 45_000L

        private const val START_TIMEOUT_MS = 10_000L
        private const val AUDIO_TIMEOUT_MS = 3_000L
        private const val MESSAGE_TIMEOUT_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 35_000L
    }
}
