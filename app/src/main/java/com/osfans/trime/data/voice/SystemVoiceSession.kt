/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 */
package com.osfans.trime.data.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber
import java.util.Locale

/**
 * 进程内调用系统 [android.speech.RecognitionService]。
 *
 * 与 [JsVoiceSession] 的区别：录音和识别都由目标服务自己完成，这里只负责下指令、转发回调。
 * 好处是不切换输入法——长按空格就能说话，松手上屏，和内置插件的手感一致。
 *
 * 生命周期对齐 [JsVoiceCallbacks]：onReady → onPartial* → onFinal → onEnded。
 */
internal class SystemVoiceSession(
    private val context: Context,
    private val component: ComponentName,
    private val onLevel: (Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var ended = false

    fun start(callbacks: JsVoiceCallbacks) {
        val created =
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }
                .getOrElse {
                    Timber.w(it, "voice: createSpeechRecognizer failed")
                    callbacks.onError(errorText(-1))
                    return
                }
        recognizer = created
        created.setRecognitionListener(listener(callbacks))
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
        runCatching { created.startListening(intent) }.onFailure {
            Timber.w(it, "voice: startListening failed")
            callbacks.onError(errorText(-1))
            destroy()
        }
    }

    /** 松手：让服务出最终结果。部分服务收到 stopListening 才返回 onResults。 */
    fun endStream() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        ended = true
        destroy()
    }

    private fun listener(callbacks: JsVoiceCallbacks) =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB 常见范围 -2..10，映射到 0..100 供波形条使用。
                val level = (((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 100f).toInt()
                onLevel(level)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (ended) return
                ended = true
                Timber.w("voice: recognition error %d", error)
                callbacks.onError(errorText(error))
                destroy()
            }

            override fun onResults(results: Bundle?) {
                if (ended) return
                ended = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    // 服务正常收尾但没给出文本：多半是没听到声音，按「没有识别到内容」提示。
                    callbacks.onError(errorText(SpeechRecognizer.ERROR_NO_MATCH))
                } else {
                    callbacks.onFinal(text)
                    callbacks.onEnded()
                }
                destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (ended) return
                val text =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                if (!text.isNullOrBlank()) callbacks.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun destroy() {
        val current = recognizer ?: return
        recognizer = null
        runCatching {
            current.setRecognitionListener(null)
            current.destroy()
        }
    }

    private fun errorText(code: Int): String =
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到内容"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "网络异常，识别服务不可用"
            SpeechRecognizer.ERROR_SERVER -> "识别服务返回错误"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
            SpeechRecognizer.ERROR_CLIENT -> "识别被中断"
            SpeechRecognizer.ERROR_AUDIO -> "录音出错"
            else -> "语音识别失败（$code）"
        }
}
