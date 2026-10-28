/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.hub

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

/** 数据与备份：用户词典、数据目录与同步。 */
class DataBackupFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.user_dictionary, R.string.home_summary_user_dict, R.drawable.ic_baseline_book_24) {
                    findNavController().navigateWithAnim(NavigationRoute.UserDict)
                }
                addPreference(R.string.profile, R.string.home_summary_profile, R.drawable.ic_baseline_snippet_folder_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Profile)
                }
            }
        }
    }
}
