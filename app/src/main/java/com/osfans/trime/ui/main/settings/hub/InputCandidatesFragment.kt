/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.hub

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

/** 输入与候选：方案、输入习惯、候选窗口。 */
class InputCandidatesFragment : PaddingPreferenceFragment() {
    private fun PreferenceGroup.link(
        title: Int,
        summary: Int,
        icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, summary, icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("") {
                isIconSpaceReserved = false
                link(R.string.schemata, R.string.home_summary_schema, R.drawable.ic_round_view_list_24, NavigationRoute.SchemaList)
                link(R.string.general, R.string.home_summary_general, R.drawable.ic_baseline_tune_24, NavigationRoute.General)
                link(R.string.candidates_window, R.string.home_summary_candidates, R.drawable.ic_baseline_list_alt_24, NavigationRoute.CandidatesWindow)
            }
        }
    }
}
