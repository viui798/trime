/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.voice

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.voice.JsVoiceField
import com.osfans.trime.data.voice.JsVoiceFields
import com.osfans.trime.data.voice.JsVoicePluginStore
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.data.voice.JsVoiceSettingsStore
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp

/**
 * 单个 JS 语音插件的设置页。
 *
 * 字段全部来自插件自己的 `configure(ui, locale)` 声明，宿主不写死任何一家服务的表单：
 * 插件升级新增字段、豆包在 apiKey/accessToken 之间切换，这一页都只是照单渲染。
 */
class VoicePluginSettingsFragment : PaddingPreferenceFragment() {

    private val pluginId: String by lazy {
        requireArguments().getString(ARG_PLUGIN_ID).orEmpty()
    }

    private var fields: List<JsVoiceField> = emptyList()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(
        view: android.view.View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        loadFields()
    }

    private fun loadFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JsVoiceProviders.describe(pluginId) }
            }
            if (!isAdded) return@launch
            result
                .onSuccess {
                    fields = it
                    render()
                }.onFailure { renderError(it) }
        }
    }

    private fun render() {
        val screen = preferenceScreen ?: return
        val ctx = requireContext()
        screen.removeAll()

        val spec = JsVoiceProviders.specOf(pluginId)
        val values = JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(pluginId))
        val visible = fields.filter { JsVoiceFields.isVisible(it, values) }

        val settings = PreferenceCategory(ctx).apply {
            setTitle(R.string.voice_plugin_settings_title)
            isIconSpaceReserved = false
        }
        screen.addPreference(settings)
        if (visible.isEmpty()) {
            settings.addPreference(
                Preference(ctx).apply {
                    isSelectable = false
                    isIconSpaceReserved = false
                    setSummary(R.string.voice_plugin_fields_empty)
                },
            )
        } else {
            visible.forEach { field ->
                settings.addPreference(fieldPreference(field, values[field.key] ?: field.defaultValue))
            }
        }

        val actions = PreferenceCategory(ctx).apply {
            setTitle(R.string.voice_plugin_actions)
            isIconSpaceReserved = false
        }
        screen.addPreference(actions)

        val current = AppPrefs.defaultInstance().general.preferredVoiceInput.getValue()
        if (current == pluginId) {
            actions.addPreference(
                Preference(ctx).apply {
                    isSelectable = false
                    isIconSpaceReserved = false
                    setTitle(R.string.voice_plugin_current_label)
                    summary = spec?.label.orEmpty()
                },
            )
        } else {
            actions.addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    setTitle(R.string.voice_plugin_set_current)
                    summary = spec?.label.orEmpty()
                    setOnPreferenceClickListener {
                        AppPrefs.defaultInstance().general.preferredVoiceInput.setValue(pluginId)
                        toast(getString(R.string.voice_plugin_set_current_done, spec?.label.orEmpty()))
                        render()
                        true
                    }
                },
            )
        }

        if (spec != null && !spec.builtIn) {
            actions.addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    setTitle(R.string.voice_plugin_remove)
                    setOnPreferenceClickListener {
                        confirmRemove(spec)
                        true
                    }
                },
            )
        }

        screen.addPreference(
            Preference(ctx).apply {
                isSelectable = false
                isIconSpaceReserved = false
                setSummary(R.string.voice_security_note)
            },
        )
    }

    private fun renderError(error: Throwable) {
        val screen = preferenceScreen ?: return
        val ctx = requireContext()
        screen.removeAll()
        screen.addPreference(
            Preference(ctx).apply {
                isSelectable = false
                isIconSpaceReserved = false
                setSummary(getString(R.string.voice_plugin_load_error, error.message.orEmpty()))
            },
        )
    }

    private fun fieldPreference(
        field: JsVoiceField,
        value: String,
    ): Preference {
        val ctx = requireContext()
        return when {
            field.isSecret -> Preference(ctx).apply {
                isIconSpaceReserved = false
                isSingleLineTitle = false
                title = field.label
                summary = if (JsVoiceSettingsStore.hasSecret(pluginId, field.key)) {
                    buildString {
                        append(getString(R.string.voice_plugin_secret_saved))
                        if (field.description.isNotBlank()) append(" · ").append(field.description)
                    }
                } else {
                    getString(R.string.voice_plugin_secret_unset)
                }
                setOnPreferenceClickListener {
                    showSecretDialog(field)
                    true
                }
            }

            field.kind == JsVoiceField.KIND_SWITCH -> SwitchPreference(ctx).apply {
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                title = field.label
                if (field.description.isNotBlank()) summary = field.description
                isChecked = value == "true"
                setOnPreferenceChangeListener { _, newValue ->
                    JsVoiceSettingsStore.setValue(pluginId, field.key, newValue?.toString().orEmpty())
                    rerender()
                    true
                }
            }

            field.kind == JsVoiceField.KIND_SELECT && field.options.isNotEmpty() -> Preference(ctx).apply {
                isIconSpaceReserved = false
                isSingleLineTitle = false
                title = field.label
                summary = field.options.firstOrNull { it.value == value }?.label ?: value
                setOnPreferenceClickListener {
                    showSelectDialog(field, value)
                    true
                }
            }

            else -> EditTextPreference(ctx).apply {
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                title = field.label
                setDefaultValue(field.defaultValue)
                if (field.kind == JsVoiceField.KIND_NUMBER) {
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                }
                text = value
                if (field.description.isNotBlank()) {
                    dialogMessage = field.description
                    summary = value.ifBlank { field.defaultValue }
                } else {
                    summary = value.ifBlank { field.defaultValue }
                }
                setOnPreferenceChangeListener { _, newValue ->
                    JsVoiceSettingsStore.setValue(pluginId, field.key, newValue?.toString().orEmpty())
                    rerender()
                    true
                }
            }
        }
    }

    private fun showSecretDialog(field: JsVoiceField) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.voice_plugin_secret_hint)
        }
        val container = FrameLayout(ctx).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(field.label)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isNotEmpty() && !JsVoiceSettingsStore.setSecret(pluginId, field.key, text)) {
                    toast(getString(R.string.voice_plugin_load_error, field.label))
                }
                render()
            }.setNeutralButton(R.string.voice_plugin_secret_clear) { _, _ ->
                JsVoiceSettingsStore.setSecret(pluginId, field.key, null)
                render()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSelectDialog(
        field: JsVoiceField,
        current: String,
    ) {
        val ctx = requireContext()
        val options = field.options
        val labels = options.map { it.label }.toMutableList()
        if (field.allowCustom) labels += getString(R.string.voice_plugin_custom)
        val checked = options.indexOfFirst { it.value == current }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(field.label)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which in options.indices) {
                    JsVoiceSettingsStore.setValue(pluginId, field.key, options[which].value)
                    render()
                } else {
                    showCustomInput(field, current)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomInput(
        field: JsVoiceField,
        current: String,
    ) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(current)
            setSingleLine()
        }
        val container = FrameLayout(ctx).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(field.label)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                JsVoiceSettingsStore.setValue(pluginId, field.key, input.text?.toString().orEmpty())
                render()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(spec: JsVoiceProviders.Spec) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.voice_plugin_remove_title, spec.label))
            .setMessage(R.string.voice_plugin_remove_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                JsVoicePluginStore.delete(pluginId.removePrefix(PLUGIN_ID_PREFIX))
                JsVoiceProviders.reload()
                if (AppPrefs.defaultInstance().general.preferredVoiceInput.getValue() == pluginId) {
                    AppPrefs.defaultInstance().general.preferredVoiceInput.setValue(JsVoiceProviders.QWEN_ASR_ID)
                }
                toast(getString(R.string.voice_plugin_removed))
                requireActivity().finish()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rerender() {
        view?.post {
            if (isAdded) render()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_PLUGIN_ID = "plugin_id"
        private const val PLUGIN_ID_PREFIX = "js:"

        fun newInstance(pluginId: String): VoicePluginSettingsFragment =
            VoicePluginSettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_PLUGIN_ID, pluginId) }
            }
    }
}
