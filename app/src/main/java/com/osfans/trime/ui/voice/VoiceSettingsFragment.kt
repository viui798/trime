/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.voice

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.voice.JsVoiceHostPermissions
import com.osfans.trime.data.voice.JsVoiceImportResult
import com.osfans.trime.data.voice.JsVoiceImporter
import com.osfans.trime.data.voice.JsVoicePluginRecord
import com.osfans.trime.data.voice.JsVoicePluginStore
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 语音插件清单：导入入口 + 内置与已导入插件平铺。 */
class VoiceSettingsFragment : PaddingPreferenceFragment() {

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri)
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // 「当前使用」与插件清单都可能在上一个页面改过，回来时重读一次。
        rebuild()
    }

    private fun rebuild() {
        val screen = preferenceScreen ?: return
        val ctx = requireContext()
        screen.removeAll()

        // 芝版同文：「首选语音输入法」原先在「常规」里，现在归到语音输入页。
        AppPrefs.defaultInstance().voice.createUi(screen)

        screen.addPreference(
            Preference(ctx).apply {
                key = "voice_import"
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setTitle(R.string.voice_import)
                setSummary(R.string.voice_import_summary)
                setOnPreferenceClickListener {
                    importLauncher.launch(arrayOf("*/*"))
                    true
                }
            },
        )

        // 键盘上长按空格现在直接是「按住说话」，首选引擎的快捷切换入口收进设置页
        screen.addPreference(
            Preference(ctx).apply {
                key = "voice_choose_engine"
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setTitle(R.string.voice_choose_engine)
                setSummary(R.string.voice_choose_engine_summary)
                setOnPreferenceClickListener {
                    com.osfans.trime.ime.dialog.VoiceInputPickerDialog.build(requireContext()) { selected ->
                        if (com.osfans.trime.ime.voice.VoiceInputManager.state().active) {
                            com.osfans.trime.ime.voice.VoiceInputManager.cancel()
                        }
                        AppPrefs.defaultInstance().voice.preferredVoiceInput.setValue(selected)
                        rebuild()
                    }.show()
                    true
                }
            },
        )

        val current = AppPrefs.defaultInstance().voice.preferredVoiceInput.getValue()
        val all = JsVoiceProviders.all()
        addGroup(screen, R.string.voice_plugins_builtin, all.filter { it.builtIn }, current)
        addGroup(screen, R.string.voice_plugins_imported, all.filterNot { it.builtIn }, current)

        screen.addPreference(
            Preference(ctx).apply {
                isPersistent = false
                isSelectable = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setSummary(R.string.voice_security_note)
            },
        )
    }

    private fun addGroup(
        screen: PreferenceScreen,
        titleRes: Int,
        specs: List<JsVoiceProviders.Spec>,
        current: String,
    ) {
        val ctx = requireContext()
        val category = PreferenceCategory(ctx).apply {
            setTitle(titleRes)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        if (specs.isEmpty()) {
            category.addPreference(
                Preference(ctx).apply {
                    isSelectable = false
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    setSummary(R.string.voice_no_plugins)
                },
            )
            return
        }
        specs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).forEach { spec ->
            category.addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = spec.label
                    val detail = getString(R.string.voice_plugin_entry_summary, spec.version, spec.originName)
                    summary = if (spec.id == current) {
                        getString(R.string.voice_plugins_current) + " · " + detail
                    } else {
                        detail
                    }
                    setOnPreferenceClickListener {
                        VoiceSettingsActivity.start(requireContext(), spec.id)
                        true
                    }
                },
            )
        }
    }

    private fun startImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val resolver = requireContext().contentResolver
            val loaded = withContext(Dispatchers.IO) { runCatching { readDocument(resolver, uri) } }
            if (!isAdded) return@launch
            val file = loaded.getOrElse {
                toast(getString(R.string.voice_import_failed_read, it.message.orEmpty()))
                return@launch
            }
            Toast
                .makeText(requireContext(), R.string.voice_import_reading, Toast.LENGTH_SHORT)
                .show()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JsVoiceImporter.inspect(
                        script = file.script,
                        locale = Locale.getDefault().toLanguageTag(),
                        builtInIds = JsVoiceProviders.builtInIds(),
                        importedIds = JsVoicePluginStore.importedIds(),
                    )
                }
            }
            if (!isAdded) return@launch
            result
                .onSuccess { outcome ->
                    when (outcome) {
                        is JsVoiceImportResult.Success -> confirmImport(outcome, file.name, file.script)
                        is JsVoiceImportResult.Failure -> toast(failureMessage(outcome))
                    }
                }.onFailure { toast(getString(R.string.voice_import_failed_load, it.message.orEmpty())) }
        }
    }

    private fun confirmImport(
        result: JsVoiceImportResult.Success,
        sourceName: String,
        script: String,
    ) {
        val meta = result.meta
        val body = buildString {
            append(
                getString(
                    R.string.voice_import_dialog_body,
                    meta.providerId,
                    meta.version.ifBlank { "-" },
                    meta.hosts.joinToString("\n"),
                    result.fieldCount,
                ),
            )
            if (meta.rejectedHosts.isNotEmpty()) {
                append("\n")
                append(getString(R.string.voice_import_dialog_rejected, meta.rejectedHosts.joinToString(", ")))
            }
            append("\n\n")
            append(getString(R.string.voice_import_dialog_note))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.voice_import_dialog_title, meta.displayName))
            .setMessage(body)
            .setPositiveButton(R.string.voice_network_install_approve) { _, _ ->
                saveImport(result, sourceName, script)
            }.setNegativeButton(R.string.voice_import_cancel, null)
            .show()
    }

    private fun saveImport(
        result: JsVoiceImportResult.Success,
        sourceName: String,
        script: String,
    ) {
        val record = JsVoicePluginRecord(
            meta = result.meta,
            sourceName = sourceName,
            importedAt = System.currentTimeMillis(),
        )
        if (!JsVoicePluginStore.save(record, script)) {
            toast(getString(R.string.voice_import_failed_read, sourceName))
            return
        }
        // 确认对话框里展示的完整主机列表就是用户批准的范围；升级插件新增主机须重新批准。
        JsVoiceHostPermissions.approve(result.meta.providerId, result.meta.hosts.toSet())
        JsVoiceProviders.reload()
        rebuild()
        toast(getString(R.string.voice_import_success, result.meta.displayName))
    }

    private fun failureMessage(failure: JsVoiceImportResult.Failure): String = when (failure.reason) {
        JsVoiceImportResult.Failure.Reason.TOO_LARGE ->
            getString(R.string.voice_import_failed_too_large, failure.detail)

        JsVoiceImportResult.Failure.Reason.LOAD_FAILED ->
            getString(R.string.voice_import_failed_load, failure.detail)

        JsVoiceImportResult.Failure.Reason.NO_PLUGIN ->
            getString(R.string.voice_import_failed_no_plugin)

        JsVoiceImportResult.Failure.Reason.NO_VOICE_FEATURE ->
            getString(R.string.voice_import_failed_no_feature)

        JsVoiceImportResult.Failure.Reason.INVALID_ID ->
            getString(R.string.voice_import_failed_invalid_id)

        JsVoiceImportResult.Failure.Reason.RESERVED_ID ->
            getString(R.string.voice_import_failed_reserved_id)

        JsVoiceImportResult.Failure.Reason.NO_HOSTS ->
            getString(R.string.voice_import_failed_no_hosts)
    }

    private data class LoadedFile(val name: String, val script: String)

    private companion object {
        const val MAX_SCRIPT_BYTES = 256 * 1024
    }

    private fun readDocument(resolver: android.content.ContentResolver, uri: Uri): LoadedFile {
        val name = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "plugin.js"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                // 先卡住体积再解析：一个几百 MB 的文件不该先被整个读进内存再拒绝。
                if (total > MAX_SCRIPT_BYTES + 1) error("script is too large")
                out.write(chunk, 0, read)
            }
            out.toByteArray()
        } ?: error("empty input stream")
        return LoadedFile(name, bytes.toString(Charsets.UTF_8))
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
