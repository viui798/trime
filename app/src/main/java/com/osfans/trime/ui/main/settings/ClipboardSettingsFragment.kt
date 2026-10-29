/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class ClipboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipboard) {
    /** 芝版同文：「剪贴板同步」从便捷功能页挪到剪贴板页下，作为它的子入口。 */
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(requireContext()).apply {
                key = "clip_sync_entry"
                isIconSpaceReserved = false
                isPersistent = false
                setTitle(R.string.clip_sync)
                setSummary(R.string.home_summary_clip_sync)
                setOnPreferenceClickListener {
                    findNavController().navigateWithAnim(NavigationRoute.ClipSync)
                    true
                }
            },
        )
    }
}
