/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.voice

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.voice.JsVoiceHostPermissions
import com.osfans.trime.data.voice.JsVoiceProviders

/**
 * 插件网络主机授权确认框。
 *
 * 调用方决定是否需要包 Material 主题：从 IME 服务弹必须用 `Context.withMaterialDialogTheme()`
 * 包一层 context（见 util/DialogTheme.kt），从设置 Activity 弹则不用。
 */
internal object VoiceHostPermissionDialog {
    fun build(
        context: Context,
        spec: JsVoiceProviders.Spec,
        onChanged: () -> Unit,
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.voice_network_title)
            .setMessage(
                context.getString(
                    R.string.voice_network_message,
                    spec.label,
                    spec.hosts.sorted().joinToString("\n"),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
        if (JsVoiceHostPermissions.isApproved(spec.id, spec.hosts)) {
            builder.setPositiveButton(R.string.voice_network_revoke) { _, _ ->
                JsVoiceHostPermissions.revoke(spec.id)
                onChanged()
            }
        } else {
            builder.setPositiveButton(R.string.voice_network_approve) { _, _ ->
                JsVoiceHostPermissions.approve(spec.id, spec.hosts)
                onChanged()
            }
        }
        return builder.create()
    }
}
