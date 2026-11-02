/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 */
package com.osfans.trime.data.voice

import android.content.ComponentName
import android.content.Intent
import android.speech.RecognitionService
import com.osfans.trime.util.appContext
import timber.log.Timber

/**
 * 把「系统语音输入法」的包名解析成它提供的 [RecognitionService] 组件。
 *
 * 说点啥（com.brycewg.asrkb）、fxliang 的 ASR 助手这类应用，除了是一个输入法，还实现了
 * `android.speech.RecognitionService`。有了这个组件就能用 [android.speech.SpeechRecognizer]
 * 在当前进程里直接调用它们，不必切换输入法。
 *
 * 结果按包名缓存：引擎列表不长，且包列表在一次输入法会话里不会变。
 */
internal object SystemAsrEngines {
    private val cache = HashMap<String, ComponentName?>()

    fun resolve(packageName: String): ComponentName? {
        if (packageName.isBlank()) return null
        cache[packageName]?.let { return it }
        val component =
            runCatching {
                val intent = Intent(RecognitionService.SERVICE_INTERFACE).setPackage(packageName)
                appContext.packageManager
                    .queryIntentServices(intent, 0)
                    .firstOrNull { it.serviceInfo?.exported == true }
                    ?.serviceInfo
                    ?.let { ComponentName(it.packageName, it.name) }
            }.onFailure {
                Timber.w(it, "voice: query RecognitionService failed for %s", packageName)
            }.getOrNull()
        cache[packageName] = component
        return component
    }

    /** 这个 id 是否能走进程内识别（内置 JS 插件或系统 RecognitionService 二选一）。 */
    fun canHandle(engineId: String): Boolean =
        engineId.isNotBlank() && (engineId.startsWith("js:") || resolve(engineId) != null)
}
