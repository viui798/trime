/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import kotlinx.coroutines.launch
import com.osfans.trime.R
import com.osfans.trime.ui.main.NavigationRoute
import androidx.navigation.fragment.findNavController

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        listOf(
            Triple(R.string.keyboard_height_settings, R.string.keyboard_height_description, NavigationRoute.KeyboardHeight),
            Triple(R.string.keyboard_safe_area, R.string.keyboard_safe_description, NavigationRoute.KeyboardSafeArea),
        ).forEachIndexed { index, (title, description, route) ->
            screen.addPreference(Preference(requireContext()).apply {
                setTitle(title)
                setSummary(description)
                isIconSpaceReserved = false
                order = -100 + index
                setOnPreferenceClickListener { findNavController().navigate(route); true }
            })
        }
        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext())
                        .show()
                }
                true
            }
        }
    }
}
