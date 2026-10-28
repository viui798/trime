/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the QuickJS network bindings for one session. Each native is a global `__n_<name>`, which
 * the prelude exposes as `foxy.*`. Calls are synchronous because QuickJS evaluation already runs
 * on an IO thread.
 *
 * 密钥从不进入 JS 上下文：插件只给出字段名，真正的值由宿主在这里按字段名取出后拼进请求头。
 */
internal class JsVoiceNet(
    private val http: OkHttpClient,
    private val declaredHosts: Set<String>,
    private val rx: Channel<JsVoiceCommand>,
    private val secretFor: (String) -> String?,
) {
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val opened = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val nextId = AtomicInteger(1)

    fun install(js: QuickJs, locale: String) {
        js.function("__n_locale") { _ -> locale }
        js.function("__n_wsConnect") { args ->
            connect(
                url = text(args, 0),
                secretField = text(args, 1),
                authHeader = text(args, 2),
                authPrefix = text(args, 3),
                headersJson = text(args, 4),
            )
        }
        js.function("__n_wsWaitOpen") { args -> awaitOpen(text(args, 0)); null }
        js.function("__n_wsSend") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(text(args, 1))
            null
        }
        js.function("__n_wsSendBinary") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(ByteString.of(*bytes(args, 1)))
            null
        }
        js.function("__n_wsSendGzipFrame") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(ByteString.of(*JsVoiceHelpers.gzipFrame(bytes(args, 1), bytes(args, 2))))
            null
        }
        js.function("__n_wsClose") { args ->
            sockets.remove(text(args, 0))?.close(1000, null)
            null
        }
        js.function("__n_httpPost") { args ->
            post(
                url = text(args, 0),
                secretField = text(args, 1),
                body = text(args, 2),
                headersJson = text(args, 3),
            )
        }
        js.function("__n_wavBase64") { args ->
            encode(JsVoiceHelpers.wavBytes(bytes(args, 0), text(args, 1).toInt()))
        }
        js.function("__n_utf8Base64") { args -> encode(text(args, 0).toByteArray(Charsets.UTF_8)) }
        js.function("__n_base64Utf8") { args -> String(bytes(args, 0), Charsets.UTF_8) }
        js.function("__n_byteLength") { args -> bytes(args, 0).size }
        js.function("__n_sliceBase64") { args ->
            val data = bytes(args, 0)
            val offset = text(args, 1).toInt().coerceIn(0, data.size)
            val length = text(args, 2).toInt().coerceIn(0, data.size - offset)
            encode(data.copyOfRange(offset, offset + length))
        }
        js.function("__n_readUInt8") { args -> bytes(args, 0)[text(args, 1).toInt()].toInt() and 0xff }
        js.function("__n_readInt32BE") { args -> JsVoiceHelpers.readInt32BE(bytes(args, 0), text(args, 1).toInt()) }
        js.function("__n_gunzipUtf8") { args -> String(JsVoiceHelpers.gunzip(bytes(args, 0)), Charsets.UTF_8) }
    }

    private fun connect(
        url: String,
        secretField: String,
        authHeader: String,
        authPrefix: String,
        headersJson: String,
    ): String {
        if (!JsVoiceHelpers.isAllowedUrl(url, declaredHosts)) {
            throw IllegalArgumentException("Host is not declared by the plugin")
        }
        val headers = JsVoiceHeaders.build(
            secretField = secretField,
            secret = secretField.takeIf { it.isNotBlank() }?.let(secretFor),
            authHeader = authHeader,
            authPrefix = authPrefix,
            headersJson = headersJson,
        )
        val id = nextId.getAndIncrement().toString()
        val gate = CompletableDeferred<Unit>()
        opened[id] = gate
        val builder = Request.Builder().url(url.replaceFirst("wss://", "https://"))
        headers.forEach { (name, value) -> builder.header(name, value) }
        sockets[id] = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                gate.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                rx.trySend(JsVoiceCommand.Message(id, text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                rx.trySend(JsVoiceCommand.Message(id, "", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                gate.completeExceptionally(t)
                rx.trySend(JsVoiceCommand.Fail("WebSocket failed: " + t.message))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(id)
            }
        })
        return id
    }

    private fun awaitOpen(id: String) {
        val gate = opened[id] ?: throw IllegalStateException("Unknown socket")
        runBlocking { withTimeout(10_000L) { gate.await() } }
    }

    private fun post(url: String, secretField: String, body: String, headersJson: String): String {
        if (!JsVoiceHelpers.isAllowedUrl(url, declaredHosts)) {
            throw IllegalArgumentException("Host is not declared by the plugin")
        }
        val headers = JsVoiceHeaders.build(
            secretField = secretField,
            secret = secretField.takeIf { it.isNotBlank() }?.let(secretFor),
            authHeader = "",
            authPrefix = "",
            headersJson = headersJson,
        )
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }

    fun closeAll() {
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear()
        opened.values.forEach { it.cancel() }
        opened.clear()
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun bytes(args: Array<Any?>, index: Int): ByteArray = Base64.decode(text(args, index), Base64.NO_WRAP)

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}
