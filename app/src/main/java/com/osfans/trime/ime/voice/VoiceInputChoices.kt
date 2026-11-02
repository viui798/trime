/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.voice

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.data.voice.SystemAsrEngines
import com.osfans.trime.util.InputMethodUtils

/**
 * 可选语音引擎的统一清单。
 *
 * 三个来源，长按语音键弹出的快捷选择框与设置页共用这一份，保证两边能选到的 id 完全一致：
 * 1. 用户导入的 JS 插件（id 形如 `js:xxx`）
 * 2. 系统语音输入法（IME 里声明了 voice 子类型的，如说点啥、Foxy）
 * 3. 系统语音识别服务（只暴露 RecognitionService 的应用，如「ASR 助手」——它不是输入法）
 *
 * 第 3 类必须单独列：ASR 助手这类应用不注册 IME，只靠 IME 列表会永远看不到它们。
 */
internal object VoiceInputChoices {
    data class Entry(val id: String, val label: String, val source: String)

    fun list(context: Context): List<Entry> {
        val plugins =
            JsVoiceProviders.all().map { spec ->
                Entry(
                    id = spec.id,
                    label = spec.label,
                    source = context.getString(R.string.voice_engine_imported),
                )
            }
        val ime =
            InputMethodUtils.voiceInputMethods().map { (info, _) ->
                // 偏好里存的是输入法包名（见 AppPrefs.Voice），这里保持同一口径。
                Entry(
                    id = info.packageName,
                    label = info.loadLabel(context.packageManager).toString(),
                    source = context.getString(R.string.voice_engine_system),
                )
            }
        val imePackages = ime.mapTo(HashSet()) { it.id }
        val recognizers =
            SystemAsrEngines.all(context)
                .filterNot { it.packageName in imePackages }
                .map {
                    Entry(
                        id = it.packageName,
                        label = it.label,
                        source = context.getString(R.string.voice_engine_recognition),
                    )
                }
        return plugins + ime + recognizers
    }
}
