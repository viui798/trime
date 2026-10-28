/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R

/**
 * 芝版同文：所有枚举 / 列表型设置统一用 Material3 悬浮对话框。
 *
 * 平台自带的 [ListPreference] 对话框是 AppCompat 小圆角、只有「取消」；
 * 这里换成 Material3 大圆角，并补一个左下角「默认」把值恢复成默认。
 */
class MaterialListPreference(
    context: Context,
) : ListPreference(context) {
    private var defaultEntryValue: String? = null

    override fun setDefaultValue(defaultValue: Any?) {
        defaultEntryValue = defaultValue?.toString()
        super.setDefaultValue(defaultValue)
    }

    override fun onClick() {
        if (!isEnabled) return
        val entries = entries ?: return
        val entryValues = entryValues ?: return
        val current = value
        val checked = entryValues.indexOfFirst { it.toString() == current }
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(dialogTitle ?: title)
                .setSingleChoiceItems(entries, checked) { dialog, which ->
                    val newValue = entryValues[which].toString()
                    if (callChangeListener(newValue)) {
                        value = newValue
                        dialog.dismiss()
                    }
                }.setNegativeButton(android.R.string.cancel, null)
        defaultEntryValue?.let { def ->
            builder.setNeutralButton(R.string.default_) { _, _ ->
                if (callChangeListener(def)) value = def
            }
        }
        builder.show()
    }
}
