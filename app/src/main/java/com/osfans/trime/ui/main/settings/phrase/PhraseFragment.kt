/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.phrase

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewModelScope
import androidx.preference.Preference
import com.osfans.trime.R
import com.osfans.trime.data.phrase.PhraseManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

/**
 * 芝版同文：快捷短语管理。
 */
class PhraseFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val phrases = mutableListOf<PhraseManager.Phrase>()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuild()
    }

    private fun rebuild() {
        phrases.clear()
        phrases.addAll(PhraseManager.load())
        val ctx = requireContext()

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.phrase_add) {
                isIconSpaceReserved = false
                addPreference(
                    R.string.phrase_add,
                    R.string.phrase_add_summary,
                    onClick = { showEditDialog(null) },
                )
            }
            addCategory(R.string.phrase_title) {
                isIconSpaceReserved = false
                if (phrases.isEmpty()) {
                    addPreference(getString(R.string.phrase_empty))
                } else {
                    phrases.forEachIndexed { index, phrase ->
                        val item =
                            Preference(ctx).apply {
                                isSingleLineTitle = false
                                title = phrase.text
                                summary =
                                    phrase.code.ifEmpty {
                                        ctx.getString(R.string.phrase_any_code)
                                    }
                                setOnPreferenceClickListener {
                                    showActions(index)
                                    true
                                }
                            }
                        addPreference(item)
                    }
                }
            }
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(
                    R.string.phrase_redeploy,
                    R.string.phrase_redeploy_summary,
                    onClick = { redeploy() },
                )
            }
        }
    }

    private fun showActions(index: Int) {
        val phrase = phrases.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(phrase.text)
            .setItems(
                arrayOf(
                    getString(R.string.phrase_edit),
                    getString(R.string.phrase_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showEditDialog(index)
                    1 -> {
                        phrases.removeAt(index)
                        persist()
                    }
                }
            }.show()
    }

    private fun showEditDialog(index: Int?) {
        val ctx = requireContext()
        val existing = index?.let { phrases.getOrNull(it) }
        val padding = (16 * resources.displayMetrics.density).toInt()

        val textInput =
            EditText(ctx).apply {
                hint = getString(R.string.phrase_text_hint)
                setText(existing?.text.orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT
            }
        val codeInput =
            EditText(ctx).apply {
                hint = getString(R.string.phrase_code_hint)
                setText(existing?.code.orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT
            }

        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(textInput)
                addView(codeInput)
            }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.phrase_add else R.string.phrase_edit)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = textInput.text.toString().trim()
                if (text.isEmpty()) {
                    ctx.toast(R.string.phrase_text_required)
                    return@setPositiveButton
                }
                val phrase = PhraseManager.Phrase(text, codeInput.text.toString().trim(), 1)
                if (index == null) {
                    phrases.add(phrase)
                } else {
                    phrases[index] = phrase
                }
                persist()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persist() {
        val ok = PhraseManager.save(phrases)
        requireContext().toast(if (ok) R.string.phrase_saved else R.string.phrase_save_failed)
        rebuild()
    }

    private fun redeploy() {
        val ctx = requireContext()
        viewModel.viewModelScope.launch {
            runCatching { viewModel.rime.runOnReady { deploy() } }
                .onSuccess { ctx.toast(R.string.phrase_deployed) }
                .onFailure { ctx.toast(R.string.phrase_deploy_failed) }
        }
    }
}
