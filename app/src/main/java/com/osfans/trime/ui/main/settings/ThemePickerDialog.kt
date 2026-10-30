// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.util.withMaterialDialogTheme
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

object ThemePickerDialog {
    suspend fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
        afterConfirm: (suspend () -> Unit)? = null,
    ): AlertDialog {
        val allThemes =
            withContext(Dispatchers.IO) {
                ThemeManager.getAllThemes()
            }
        val selectedTheme by ThemeManager.prefs.selectedTheme
        val selectedIndex = allThemes.indexOfFirst { it.configId == selectedTheme }
        return MaterialAlertDialogBuilder(context.withMaterialDialogTheme())
            .apply {
                setTitle(R.string.selected_theme)
                if (allThemes.isEmpty()) {
                    setMessage(R.string.no_theme_to_select)
                } else {
                    setSingleChoiceItems(
                        allThemes.map { it.name }.toTypedArray(),
                        selectedIndex,
                    ) { dialog, which ->
                        val newItem = allThemes[which]
                        scope.launch {
                            afterConfirm?.invoke()
                            // 切换主题要读文件、可能失败；失败就保留当前主题并提示，
                            // 不要让异常逃出协程把应用带崩（用户看到的是闪退）。
                            val resolvedThemeId = runCatching {
                                UserDataManager.sync()
                                ThemeManager.selectTheme(newItem.configId)
                            }.getOrElse { e ->
                                Timber.w(e, "Failed to switch theme to ${newItem.configId}")
                                runCatching { dialog.dismiss() }
                                context.toast(context.getString(R.string.theme_switch_failed, newItem.name), Toast.LENGTH_LONG)
                                return@launch
                            }
                            dialog.dismiss()
                            if (resolvedThemeId != newItem.configId) {
                                // Chosen theme unavailable: report the one actually in effect.
                                val fallbackName =
                                    allThemes.firstOrNull { it.configId == resolvedThemeId }?.name
                                        ?: resolvedThemeId
                                context.toast(
                                    context.getString(
                                        R.string.theme_unavailable_fallback,
                                        newItem.name,
                                        fallbackName,
                                    ),
                                    Toast.LENGTH_LONG,
                                )
                            }
                        }
                    }
                }
                setNegativeButton(android.R.string.cancel, null)
            }.create()
    }
}
