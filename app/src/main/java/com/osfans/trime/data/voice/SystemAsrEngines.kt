/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 */
package com.osfans.trime.data.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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

    data class Entry(
        val packageName: String,
        val label: String,
        val component: ComponentName,
    )

    /**
     * 扫描所有提供了 RecognitionService 的应用。
     *
     * 「ASR 助手」这类应用只声明识别服务、不注册输入法，靠 IME 列表永远看不到它们，
     * 所以语音引擎列表要单独扫这一份。
     */
    fun all(context: Context): List<Entry> =
        runCatching {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            context.packageManager
                .queryIntentServices(intent, 0)
                .mapNotNull { info ->
                    val service = info.serviceInfo ?: return@mapNotNull null
                    Entry(
                        packageName = service.packageName,
                        label = info.loadLabel(context.packageManager).toString(),
                        component = ComponentName(service.packageName, service.name),
                    )
                }.distinctBy { it.packageName }
        }.onFailure {
            Timber.w(it, "voice: query all RecognitionService failed")
        }.getOrDefault(emptyList())

    /** 系统设置的默认语音识别服务（没有则返回 null）。 */
    fun defaultPackage(): String? =
        runCatching {
            Settings.Secure.getString(appContext.contentResolver, "voice_recognition_service")
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
}
