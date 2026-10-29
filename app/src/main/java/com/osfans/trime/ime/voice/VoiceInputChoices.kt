/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.voice

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.util.InputMethodUtils

/**
 * 可选语音引擎的统一清单。
 *
 * 长按语音键弹出的快捷选择框与设置页共用这一份列表，保证两边能选到的引擎 id 完全一致。
 */
internal object VoiceInputChoices {
    data class Entry(val id: String, val label: String, val source: String)

    fun list(context: Context): List<Entry> {
        val plugins = JsVoiceProviders.all().map { spec ->
            Entry(
                id = spec.id,
                label = spec.label,
                source = context.getString(
                    if (spec.builtIn) R.string.voice_engine_builtin else R.string.voice_engine_imported,
                ),
            )
        }
        val system = InputMethodUtils.voiceInputMethods().map { (info, _) ->
            // 偏好里存的是输入法包名（见 AppPrefs.Voice），这里保持同一口径。
            Entry(
                id = info.packageName,
                label = info.loadLabel(context.packageManager).toString(),
                source = context.getString(R.string.voice_engine_system),
            )
        }
        return plugins + system
    }
}
