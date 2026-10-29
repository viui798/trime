/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.osfans.trime.R
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.setup.SetupActivity
import com.osfans.trime.ui.voice.VoiceSettingsActivity
import com.osfans.trime.util.SearchPreference
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim
import com.osfans.trime.util.startActivity
import splitties.systemservices.inputMethodManager

/**
 * 芝版同文：设置主页（版式参考中州鹅）。
 *
 * 顶部是键盘状态卡（未添加 / 未启用 / 正常），下面才是「日常调整」「按需进入」两组入口；
 * 顶部搜索框按标题/摘要过滤当前列表。
 */
class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    /** 搜索无结果时的占位行。 */
    private var emptyPreference: Preference? = null

    /** 顶部键盘状态卡。 */
    private var statusBar: HomeStatusBar? = null

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBar()
    }

    private fun PreferenceGroup.addDestination(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        @StringRes summary: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, summary, icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    private fun PreferenceGroup.addActivity(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        @StringRes summary: Int,
        activity: Class<*>,
    ) {
        addPreference(title, summary, icon) {
            startActivity(Intent(requireContext(), activity))
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            statusBar = HomeStatusBar(requireContext())
            addPreference(statusBar!!)
            addPreference(SearchPreference(requireContext()) { filter(it) })
            val empty =
                Preference(requireContext()).apply {
                    isPersistent = false
                    isIconSpaceReserved = false
                    isSelectable = false
                    layoutResource = R.layout.preference_settings_empty
                    isVisible = false
                }
            emptyPreference = empty
            addPreference(empty)

            addCategory(R.string.home_group_daily) {
                isIconSpaceReserved = false
                addDestination(
                    R.string.input_and_candidates,
                    R.drawable.ic_baseline_tune_24,
                    R.string.home_summary_input,
                    NavigationRoute.InputCandidates,
                )
                addDestination(
                    R.string.appearance,
                    R.drawable.ic_baseline_color_lens_24,
                    R.string.home_summary_appearance,
                    NavigationRoute.Appearance,
                )
                addDestination(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    R.string.home_summary_keyboard,
                    NavigationRoute.VirtualKeyboard,
                )
                addDestination(
                    R.string.utilities,
                    R.drawable.ic_baseline_list_alt_24,
                    R.string.home_summary_utilities,
                    NavigationRoute.Utilities,
                )
            }

            addCategory(R.string.home_group_optional) {
                isIconSpaceReserved = false
                addDestination(
                    R.string.data_and_backup,
                    R.drawable.ic_baseline_snippet_folder_24,
                    R.string.home_summary_data,
                    NavigationRoute.DataBackup,
                )
                addDestination(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    R.string.home_summary_advanced,
                    NavigationRoute.Advanced,
                )
            }
        }
    }

    /** 按标题/摘要过滤；空分类自动隐藏，搜索框与状态卡本身永远可见。 */
    private fun filter(raw: String) {
        val query = raw.trim()
        val screen = preferenceScreen ?: return
        var anyVisible = query.isEmpty()
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is SearchPreference, emptyPreference, statusBar -> Unit
                is PreferenceGroup -> {
                    preference.isVisible = filterGroup(preference, query)
                    if (preference.isVisible) anyVisible = true
                }
                else -> {
                    preference.isVisible = matches(preference, query)
                    if (preference.isVisible) anyVisible = true
                }
            }
        }
        emptyPreference?.isVisible = !anyVisible
    }

    private fun filterGroup(group: PreferenceGroup, query: String): Boolean {
        var anyVisible = false
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            val visible =
                when (child) {
                    is PreferenceGroup -> filterGroup(child, query)
                    else -> matches(child, query)
                }
            child.isVisible = visible
            if (visible) anyVisible = true
        }
        return anyVisible
    }

    private fun matches(preference: Preference, query: String): Boolean {
        if (query.isEmpty()) return true
        return preference.title?.contains(query, ignoreCase = true) == true ||
            preference.summary?.contains(query, ignoreCase = true) == true
    }

    // region 键盘状态卡

    private enum class Status {
        /** 外部存储模式还没选目录，Rime 起不来，最优先提示。 */
        STORAGE,

        /** 键盘没在系统里启用。 */
        NOT_ADDED,

        /** 已启用但不是当前输入法。 */
        NOT_CURRENT,

        /** 已启用且是当前输入法。 */
        OK,
    }

    private fun currentStatus(): Status =
        when {
            !UserDataManager.isStorageAvailable() -> Status.STORAGE
            inputMethodManager == null -> Status.NOT_ADDED
            inputMethodManager.enabledInputMethodList.none { it.packageName == requireContext().packageName } -> Status.NOT_ADDED
            Settings
                .Secure
                .getString(requireContext().contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.contains(requireContext().packageName) != true -> Status.NOT_CURRENT
            else -> Status.OK
        }

    private fun isStatusDismissed(): Boolean =
        requireContext()
            .getSharedPreferences(STATUS_PREFS, 0)
            .getBoolean(STATUS_KEY_DISMISSED, false)

    private fun setStatusDismissed(dismissed: Boolean) {
        requireContext()
            .getSharedPreferences(STATUS_PREFS, 0)
            .edit { putBoolean(STATUS_KEY_DISMISSED, dismissed) }
    }

    /** 重新计算状态并刷新卡片；正常态被手动关掉后就不再显示，直到状态变化。 */
    private fun refreshStatusBar() {
        val status = currentStatus()
        if (status != Status.OK && isStatusDismissed()) {
            setStatusDismissed(false)
        }
        statusBar?.isVisible = status != Status.OK || !isStatusDismissed()
        statusBar?.refresh()
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return ContextCompat.getColor(requireContext(), tv.resourceId)
    }

    private fun cardBackground(color: Int) =
        GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(color)
        }

    private fun renderStatusBar(holder: PreferenceViewHolder) {
        val ctx = requireContext()
        val card = holder.findViewById(R.id.home_status_card)
        val iconBadge = holder.findViewById(R.id.home_status_icon_badge)
        val icon = holder.findViewById(R.id.home_status_icon) as ImageView
        val title = holder.findViewById(R.id.home_status_title) as TextView
        val summary = holder.findViewById(R.id.home_status_summary) as TextView
        val action = holder.findViewById(R.id.home_status_action) as Button
        val dismiss = holder.findViewById(R.id.home_status_dismiss) as ImageView

        val status = currentStatus()
        val error = attrColor(com.google.android.material.R.attr.colorError)
        val onError = attrColor(com.google.android.material.R.attr.colorOnError)
        val errorContainer = attrColor(com.google.android.material.R.attr.colorErrorContainer)
        val primary = attrColor(androidx.appcompat.R.attr.colorPrimary)
        val surface = attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)

        @StringRes var titleRes: Int
        @StringRes var summaryRes: Int
        @StringRes var actionRes: Int
        var warning = true
        when (status) {
            Status.STORAGE -> {
                titleRes = R.string.home_status_storage_title
                summaryRes = R.string.home_status_storage_summary
                actionRes = R.string.home_status_storage_action
            }
            Status.NOT_ADDED -> {
                titleRes = R.string.home_status_not_added_title
                summaryRes = R.string.home_status_not_added_summary
                actionRes = R.string.home_status_not_added_action
            }
            Status.NOT_CURRENT -> {
                titleRes = R.string.home_status_not_current_title
                summaryRes = R.string.home_status_not_current_summary
                actionRes = R.string.home_status_not_current_action
            }
            Status.OK -> {
                titleRes = R.string.home_status_added_title
                summaryRes = 0
                actionRes = 0
                warning = false
            }
        }

        card.background =
            cardBackground(if (warning) errorContainer else surface)
        iconBadge.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (warning) error else primary)
            }
        val iconRes =
            when (status) {
                Status.STORAGE, Status.NOT_ADDED, Status.NOT_CURRENT -> R.drawable.ic_baseline_keyboard_24
                Status.OK -> R.drawable.ic_baseline_check_24
            }
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(if (warning) onError else Color.WHITE)
        title.setTextColor(if (warning) onError else attrColor(android.R.attr.textColorPrimary))
        summary.setTextColor(if (warning) onError else attrColor(android.R.attr.textColorSecondary))

        title.setText(titleRes)
        summary.setText(if (summaryRes == 0) R.string.trime_app_slogan else summaryRes)
        summary.isVisible = summaryRes != 0

        action.isVisible = actionRes != 0
        if (actionRes != 0) {
            action.setText(actionRes)
            action.backgroundTintList = ColorStateList.valueOf(error)
            action.setTextColor(onError)
        }

        dismiss.isVisible = status == Status.OK
        dismiss.setOnClickListener {
            setStatusDismissed(true)
            statusBar?.isVisible = false
        }

        val onClick: () -> Unit = {
            when (status) {
                Status.STORAGE -> startActivity<SetupActivity>()
                Status.NOT_ADDED -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                Status.NOT_CURRENT -> inputMethodManager?.showInputMethodPicker()
                Status.OK -> Unit
            }
        }
        action.setOnClickListener { onClick() }
        card.setOnClickListener { onClick() }
    }

    /** 主页顶部状态卡：无固定标题，视觉全由 [renderStatusBar] 按状态绘制。 */
    private inner class HomeStatusBar(
        ctx: Context,
    ) : Preference(ctx) {
        init {
            layoutResource = R.layout.view_home_status
            isPersistent = false
            isSelectable = false
            isIconSpaceReserved = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            renderStatusBar(holder)
        }

        /** notifyChanged 是 protected，包一层给 [refreshStatusBar] 用。 */
        fun refresh() = notifyChanged()
    }

    // endregion

    companion object {
        private const val STATUS_PREFS = "zhi_home_status"
        private const val STATUS_KEY_DISMISSED = "dismissed"
    }
}
