/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.util.InputMethodUtils

enum class SetupPage {
    Mode,
    Schema,
    Skin,
    Enable,
    Select,
    ;

    fun getStepText(context: Context) = context.getText(
        when (this) {
            Mode -> R.string.setup__step_one
            Schema -> R.string.setup__step_two
            Skin -> R.string.setup__step_three
            Enable -> R.string.setup__step_four
            Select -> R.string.setup__step_five
        },
    )

    /** 向导页的大图标（中州鹅式版式）。 */
    fun getIconRes(): Int = when (this) {
        Mode -> R.drawable.ic_baseline_snippet_folder_24
        Schema -> R.drawable.ic_baseline_text_fields_24
        Skin -> R.drawable.ic_baseline_color_lens_24
        Enable -> R.drawable.ic_baseline_keyboard_24
        Select -> R.drawable.ic_baseline_check_24
    }

    fun getTitleText(context: Context) = context.getText(
        when (this) {
            Mode -> R.string.setup__title_mode
            Schema -> R.string.setup__title_schema
            Skin -> R.string.setup__title_skin
            Enable -> R.string.setup__title_enable
            Select -> R.string.setup__title_select
        },
    )

    fun getHintText(context: Context) = context.getText(
        when (this) {
            Mode -> R.string.setup__select_data_path_hint
            Schema -> R.string.setup__import_schema_hint
            Skin -> R.string.setup__import_skin_hint
            Enable -> R.string.setup__enable_ime_hint
            Select -> R.string.setup__select_ime_hint
        },
    )

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            Mode -> R.string.setup__select_data_path
            Schema -> R.string.setup__import_schema
            Skin -> R.string.setup__import_skin
            Enable -> R.string.setup__enable_ime
            Select -> R.string.setup__select_ime
        },
    )

    fun getButtonAction(activity: FragmentActivity) {
        when (this) {
            Mode -> (activity as SetupActivity).pickOrActivateStorage()
            Schema -> (activity as SetupActivity).pickRimeResources()
            Skin -> (activity as SetupActivity).pickRimeResources()
            Enable -> InputMethodUtils.showImeEnablerActivity(activity)
            Select -> InputMethodUtils.showImePicker()
        }
    }

    fun showActionButton(): Boolean = when (this) {
        // 存储页只要有待办（外部同步选目录 / 直接外部授权激活）就显示按钮；应用专属无需操作。
        Mode -> true
        else -> true
    }

    /** 皮肤是选填的：不导入就用内置的芝色皮肤，不该卡住向导。 */
    fun isOptional(): Boolean = this == Skin

    fun isDone() = when (this) {
        Mode -> UserDataManager.isStorageChoiceDone()
        Schema -> hasImportedSchema()
        Skin -> hasImportedTheme()
        Enable -> InputMethodUtils.checkIsTrimeEnabled()
        Select -> InputMethodUtils.checkIsTrimeSelected()
    }

    companion object {
        fun hasImportedSchema() = UserDataManager.listSchemaIds().isNotEmpty()

        fun hasImportedTheme(): Boolean =
            DataManager.userDataDir
                .listFiles { _, name -> name.endsWith("trime.yaml") && name != "trime.yaml" }
                ?.isNotEmpty() == true

        fun SetupPage.isLastPage() = this == entries.last()

        fun Int.isLastPage() = this == entries.size - 1

        /** 只有必填页没完成才算「没做完」；皮肤页不影响能否完成。 */
        fun hasUndonePage() = entries.any { !it.isOptional() && !it.isDone() }

        /** 但引导仍应停在第一个没做的页（含选填页），否则用户根本看不到皮肤那一步。 */
        fun firstUndonePage() = entries.firstOrNull { !it.isDone() }
    }
}
