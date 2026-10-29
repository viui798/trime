/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import com.osfans.trime.R

/**
 * 给任意 context 套上 Material3 应用主题。
 *
 * 实机教训：键盘模块 DI 注入的 [ContextThemeWrapper] 来自
 * `BaseInputView.themedContext`（`android.R.style.Theme_DeviceDefault_Settings`），
 * 它是平台主题而非 AppCompat/MaterialComponents 后代。在 IME 内直接用它构建
 * `MaterialAlertDialogBuilder` 会抛 IllegalArgumentException
 * （"The style on this component requires your app theme to be Theme.AppCompat"）
 * 并杀死整个输入法进程。
 *
 * 所有从 IME（键盘上划、菜单、剪贴板）弹出的 Material 对话框，构建前必须用本函数
 * 包一层 context；在设置页（Activity 本身已是 Material3 主题）包一层也无害。
 */
fun Context.withMaterialDialogTheme(): Context = ContextThemeWrapper(this, R.style.Theme_TrimeAppTheme)
