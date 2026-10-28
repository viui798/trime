/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.voice.VoiceSettingsActivity
import com.osfans.trime.util.SearchPreference
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

/**
 * 芝版同文：设置主页（版式参考中州鹅）。
 *
 * 只留「日常调整」和「按需进入」两组入口，具体功能收进二级汇总页；
 * 顶部搜索框按标题/摘要过滤当前列表。
 */
class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    /** 搜索无结果时的占位行。 */
    private var emptyPreference: Preference? = null

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
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

    /** 按标题/摘要过滤；空分类自动隐藏，搜索框本身永远可见。 */
    private fun filter(raw: String) {
        val query = raw.trim()
        val screen = preferenceScreen ?: return
        var anyVisible = query.isEmpty()
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is SearchPreference, emptyPreference -> Unit
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
}
