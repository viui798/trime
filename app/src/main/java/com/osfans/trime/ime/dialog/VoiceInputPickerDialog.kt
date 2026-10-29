/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.voice.VoiceInputChoices
import com.osfans.trime.util.withMaterialDialogTheme

/**
 * 长按语音键弹出的首选引擎快捷切换框。
 *
 * 列表来自 [VoiceInputChoices]（与设置页同源）；选中即写回「首选语音输入」偏好，
 * 切换前的旧会话由回调方负责停止——这里不碰录音，长按本身不触发识别。
 */
object VoiceInputPickerDialog {
    fun build(
        context: Context,
        onSelected: (String) -> Unit,
    ): AlertDialog {
        val entries = VoiceInputChoices.list(context)
        val current = AppPrefs.defaultInstance().voice.preferredVoiceInput.getValue()
        val labels = entries.map { it.label + "\n" + it.source }
        return MaterialAlertDialogBuilder(context.withMaterialDialogTheme())
            .setTitle(R.string.voice_choose_engine)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                entries.indexOfFirst { it.id == current },
            ) { dialog, index ->
                dialog.dismiss()
                val id = entries[index].id
                if (id != current) onSelected(id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
