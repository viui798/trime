/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive

/**
 * 只给设置页 / 导入流程用的占位原生实现：没有任何网络与音频能力。
 *
 * 前奏里的 __native 对象在求值那一刻就把 __n_locale、__n_wsConnect 等名字全部解引用一遍，
 * 缺一个就是 ReferenceError。设置页只想读 configure() 声明的字段，却必须先把这套壳装齐。
 */
internal object JsVoiceStubNatives {
    fun install(js: QuickJs, locale: String) {
        js.function("__n_locale") { locale }
        js.function("__n_wsConnect") { throw IllegalStateException("network is not available here") }
        js.function("__n_wsWaitOpen") { null }
        js.function("__n_wsSend") { null }
        js.function("__n_wsSendBinary") { null }
        js.function("__n_wsSendGzipFrame") { null }
        js.function("__n_wsClose") { null }
        js.function("__n_httpPost") { throw IllegalStateException("network is not available here") }
        js.function("__n_hmacSha1Base64") { throw IllegalStateException("signing is not available here") }
        js.function("__n_wavBase64") { args ->
            encode(JsVoiceHelpers.wavBytes(decode(text(args, 0)), text(args, 1).toInt()))
        }
        js.function("__n_utf8Base64") { args -> encode(text(args, 0).toByteArray(Charsets.UTF_8)) }
        js.function("__n_base64Utf8") { args -> String(decode(text(args, 0)), Charsets.UTF_8) }
        js.function("__n_byteLength") { args -> decode(text(args, 0)).size }
        js.function("__n_sliceBase64") { args ->
            val data = decode(text(args, 0))
            val offset = text(args, 1).toInt().coerceIn(0, data.size)
            val length = text(args, 2).toInt().coerceIn(0, data.size - offset)
            encode(data.copyOfRange(offset, offset + length))
        }
        js.function("__n_readUInt8") { args -> decode(text(args, 0))[text(args, 1).toInt()].toInt() and 0xff }
        js.function("__n_readInt32BE") { args ->
            JsVoiceHelpers.readInt32BE(decode(text(args, 0)), text(args, 1).toInt())
        }
        js.function("__n_gunzipUtf8") { args -> String(JsVoiceHelpers.gunzip(decode(text(args, 0))), Charsets.UTF_8) }
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}

/** 在自己的 QuickJS 实例里跑一遍脚本，把 configure() 声明的设置项读出来。 */
internal object JsVoiceScript {
    suspend fun describe(script: String, locale: String): List<JsVoiceField> {
        val js = QuickJs.create(Dispatchers.IO)
        return try {
            JsVoiceStubNatives.install(js, locale)
            js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + script)
            JsVoiceFields.parse(js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")"))
        } finally {
            runCatching { js.close() }
        }
    }
}
