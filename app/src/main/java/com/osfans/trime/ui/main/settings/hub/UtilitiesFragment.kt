/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.hub

import android.content.Intent
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.InputStatsActivity
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.ui.voice.VoiceSettingsActivity
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

/** 便捷功能：短语、剪贴板、语音与输入统计。 */
class UtilitiesFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.phrase_title, R.string.home_summary_phrase, R.drawable.ic_baseline_format_quote_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Phrase)
                }
                addPreference(R.string.clipboard, R.string.home_summary_clipboard, R.drawable.ic_clipboard_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Clipboard)
                }
                addPreference(R.string.clip_sync, R.string.home_summary_clip_sync, R.drawable.ic_baseline_sync_24) {
                    findNavController().navigateWithAnim(NavigationRoute.ClipSync)
                }
                addPreference(R.string.voice_input, R.string.voice_input_summary, R.drawable.ic_baseline_mic_24) {
                    startActivity(Intent(requireContext(), VoiceSettingsActivity::class.java))
                }
                addPreference(R.string.input_stats, R.string.home_summary_stats, R.drawable.ic_baseline_bar_chart_24) {
                    startActivity(Intent(requireContext(), InputStatsActivity::class.java))
                }
            }
        }
    }
}
