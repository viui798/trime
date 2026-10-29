/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.SharedPreferences
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.SyncFilterPrefs
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import java.util.Locale

/**
 * 同步过滤子页（移植自中州鹅 clipboardsync/ui/SettingsActivity.kt 的
 * SyncFilterSettingsFragment）：文本最少/最多字符、忽略后缀、最大文件大小、规则预览。
 */
class ClipSyncFilterFragment : PaddingPreferenceFragment() {
    private val previewListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in observedKeys) {
                updateFilterPreview()
            }
        }

    private val observedKeys =
        setOf(
            SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS,
            SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE,
            SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT,
            SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS,
            SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS,
        )

    override fun onCreatePreferences(
        savedInstanceState: android.os.Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                buildUi(screen)
            }
        val prefs = preferenceManager.sharedPreferences ?: return
        SyncFilterPrefs.ensureDefaults(prefs)
        bindRows()
        updateFilterPreview()
    }

    private fun buildUi(screen: PreferenceScreen) {
        val context = screen.context

        screen.addPreference(
            Preference(context).apply {
                key = "clip_sync_filter_hint"
                isPersistent = false
                isIconSpaceReserved = false
                isSelectable = false
                setTitle(R.string.clip_sync_filter_hint_title)
                setSummary(R.string.clip_sync_filter_hint_summary)
            },
        )

        val textCategory =
            PreferenceCategory(context).apply {
                key = "clip_sync_filter_category_text"
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_category_text)
            }
        screen.addPreference(textCategory)
        textCategory.addPreference(
            EditTextPreference(context).apply {
                key = SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_min_text_chars_title)
                setSummary(R.string.clip_sync_filter_min_text_chars_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            },
        )
        textCategory.addPreference(
            EditTextPreference(context).apply {
                key = SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_max_text_chars_title)
                setSummary(R.string.clip_sync_filter_max_text_chars_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            },
        )

        val binaryCategory =
            PreferenceCategory(context).apply {
                key = "clip_sync_filter_category_binary"
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_category_binary)
            }
        screen.addPreference(binaryCategory)
        binaryCategory.addPreference(
            EditTextPreference(context).apply {
                key = SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_blocked_extensions_title)
                setSummary(R.string.clip_sync_filter_blocked_extensions_summary)
            },
        )
        binaryCategory.addPreference(
            Preference(context).apply {
                key = KEY_MAX_FILE_SIZE_ENTRY
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_max_file_size_title)
                setSummary(R.string.clip_sync_filter_max_file_size_summary)
                setOnPreferenceClickListener {
                    showMaxFileSizeDialog()
                    true
                }
            },
        )

        val previewCategory =
            PreferenceCategory(context).apply {
                key = "clip_sync_filter_category_preview"
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_category_preview)
            }
        screen.addPreference(previewCategory)
        previewCategory.addPreference(
            Preference(context).apply {
                key = SyncFilterPrefs.PREF_FILTER_PREVIEW
                isPersistent = false
                isIconSpaceReserved = false
                isSelectable = false
                setTitle(R.string.clip_sync_filter_preview_title)
                setSummary(R.string.clip_sync_filter_preview_empty)
            },
        )
    }

    private fun bindRows() {
        val prefs = preferenceManager.sharedPreferences ?: return

        findPreference<EditTextPreference>(SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val normalized =
                    newValue?.toString()
                        .orEmpty()
                        .split(Regex("[,\\s]+"))
                        .map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                preferenceManager.sharedPreferences?.edit()
                    ?.putString(SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS, normalized)
                    ?.apply()
                updateFilterPreview()
                false
            }

        listOf(
            SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS to SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS,
            SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS to SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS,
        ).forEach { (key, peerKey) ->
            findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                persistNormalizedNumericValue(changedKey = key, peerKey = peerKey, newValue = newValue?.toString())
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(previewListener)
        updateFilterPreview()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(previewListener)
        super.onPause()
    }

    private fun persistNormalizedNumericValue(
        changedKey: String,
        peerKey: String,
        newValue: String?,
    ) {
        val prefs = preferenceManager.sharedPreferences ?: return
        val normalizedValue = newValue?.trim().orEmpty()
        val changedNumber = normalizedValue.toLongOrNull()?.takeIf { it > 0 }
        val peerNumber = prefs.getString(peerKey, null)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

        val editor = prefs.edit()
        if (normalizedValue.isBlank() || changedNumber == null) {
            editor.putString(changedKey, "")
        } else if (peerNumber != null && isMinKey(changedKey) && changedNumber > peerNumber) {
            editor.putString(changedKey, peerNumber.toString())
            editor.putString(peerKey, changedNumber.toString())
        } else if (peerNumber != null && !isMinKey(changedKey) && changedNumber < peerNumber) {
            editor.putString(changedKey, peerNumber.toString())
            editor.putString(peerKey, changedNumber.toString())
        } else {
            editor.putString(changedKey, changedNumber.toString())
        }
        editor.apply()
        syncNumericPreferenceText(changedKey)
        syncNumericPreferenceText(peerKey)
        updateFilterPreview()
    }

    private fun persistPositiveNumericValue(
        key: String,
        newValue: String?,
    ) {
        val prefs = preferenceManager.sharedPreferences ?: return
        val normalizedValue = newValue?.trim().orEmpty()
        val number = normalizedValue.toLongOrNull()?.takeIf { it > 0 }
        prefs
            .edit()
            .putString(key, number?.toString().orEmpty())
            .apply()
        syncNumericPreferenceText(key)
        updateFilterPreview()
    }

    private fun syncNumericPreferenceText(key: String) {
        val value = preferenceManager.sharedPreferences?.getString(key, "").orEmpty()
        findPreference<EditTextPreference>(key)?.text = value
    }

    private fun isMinKey(key: String): Boolean = key == SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS

    private fun updateFilterPreview() {
        val prefs = preferenceManager.sharedPreferences ?: return
        findPreference<Preference>(SyncFilterPrefs.PREF_FILTER_PREVIEW)?.summary =
            SyncFilterPrefs.buildPreview(requireContext(), prefs)
        findPreference<Preference>(KEY_MAX_FILE_SIZE_ENTRY)?.summary = buildMaxFileSizeSummary(prefs)
    }

    private fun showMaxFileSizeDialog() {
        val context = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val state = SyncFilterPrefs.loadState(prefs)

        val density = context.resources.displayMetrics.density
        val paddingH = (20 * density).toInt()
        val paddingV = (12 * density).toInt()
        val spacing = (12 * density).toInt()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(paddingH, paddingV, paddingH, paddingV)
            }
        val valueInput =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.clip_sync_filter_max_file_size_title)
                setText(state.maxFileSizeValue?.toString().orEmpty())
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = spacing
                    }
            }
        val unitSpinner =
            Spinner(context).apply {
                adapter =
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_dropdown_item,
                        SyncFilterPrefs.FileSizeUnit.entries.map { it.prefValue },
                    )
                setSelection(
                    SyncFilterPrefs.FileSizeUnit.entries.indexOf(state.maxFileSizeUnit).coerceAtLeast(0),
                )
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        container.addView(valueInput)
        container.addView(unitSpinner)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.clip_sync_filter_max_file_size_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                persistPositiveNumericValue(
                    key = SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE,
                    newValue = valueInput.text?.toString(),
                )
                prefs
                    .edit()
                    .putString(
                        SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT,
                        SyncFilterPrefs.FileSizeUnit.entries[unitSpinner.selectedItemPosition].prefValue,
                    )
                    .apply()
                updateFilterPreview()
            }
            .show()
    }

    private fun buildMaxFileSizeSummary(prefs: SharedPreferences): String {
        val state = SyncFilterPrefs.loadState(prefs)
        val sizeValue = state.maxFileSizeValue ?: return getString(R.string.clip_sync_filter_max_file_size_summary)
        return getString(
            R.string.clip_sync_filter_max_file_size_value,
            sizeValue.toString(),
            state.maxFileSizeUnit.prefValue,
        )
    }

    companion object {
        private const val KEY_MAX_FILE_SIZE_ENTRY = "clip_sync_filter_max_file_size_entry"
    }
}
