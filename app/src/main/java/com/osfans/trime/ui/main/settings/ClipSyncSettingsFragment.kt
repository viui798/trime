/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.ClipSyncService
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板同步（SyncClipboard 协议）设置页，行集对齐中州鹅：
 * 开启同步 / 服务端 / 服务器地址 / 同步账户 / 同步间隔 / 同步过滤 /
 * 同步新截屏 / 存储位置 / 测试连接 / 测试推送。
 */
class ClipSyncSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipSync) {
    private val clipPrefs get() = AppPrefs.defaultInstance().clipSync

    private val storagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            clipPrefs.storageUri.setValue(uri.toString())
            refreshStorageSummary()
        }

    private val imagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(requireContext(), R.string.clip_sync_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<EditTextPreference>(AppPrefs.ClipSync.PASSWORD)?.let { pref ->
            pref.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> {
                    if (it.text.isNullOrBlank()) {
                        it.context.getString(R.string.clip_sync_not_set)
                    } else {
                        "••••••"
                    }
                }
            pref.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        screen.findPreference<Preference>(AppPrefs.ClipSync.SCREENSHOT_SYNC)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                imagePermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
            true
        }
        addStorageRow(screen)
        addTestRows(screen)
    }

    private fun addStorageRow(screen: PreferenceScreen) {
        val pref =
            Preference(screen.context).apply {
                key = STORAGE_KEY
                title = getString(R.string.clip_sync_storage)
                isPersistent = false
                summary = storageSummary()
                setOnPreferenceClickListener {
                    runCatching { storagePicker.launch(null) }
                    true
                }
            }
        screen.addPreference(pref)
    }

    private fun storageSummary(): String {
        val raw = clipPrefs.storageUri.getValue()
        if (raw.isBlank()) return getString(R.string.clip_sync_storage_default)
        return runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(raw)) }.getOrNull() ?: raw
    }

    private fun refreshStorageSummary() {
        findPreference<Preference>(STORAGE_KEY)?.summary = storageSummary()
    }

    private fun addTestRows(screen: PreferenceScreen) {
        val testConnection =
            Preference(screen.context).apply {
                key = TEST_CONNECTION_KEY
                isPersistent = false
                title = getString(R.string.clip_sync_test_connection)
                summary = getString(R.string.clip_sync_test_connection_hint)
                setOnPreferenceClickListener {
                    val address = clipPrefs.serverAddress.getValue()
                    if (address.isBlank()) {
                        Toast.makeText(context, R.string.clip_sync_test_no_address, Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        ClipSyncService.testConnection(
                                            address,
                                            clipPrefs.username.getValue(),
                                            clipPrefs.password.getValue(),
                                        )
                                    }
                                }
                            val message =
                                result.fold(
                                    { getString(R.string.clip_sync_test_connection_ok, it) },
                                    { getString(R.string.clip_sync_test_connection_fail, it.message ?: it.toString()) },
                                )
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                    true
                }
            }
        val testPush =
            Preference(screen.context).apply {
                key = TEST_PUSH_KEY
                isPersistent = false
                title = getString(R.string.clip_sync_test_push)
                summary = getString(R.string.clip_sync_test_push_hint)
                setOnPreferenceClickListener {
                    val address = clipPrefs.serverAddress.getValue()
                    if (address.isBlank()) {
                        Toast.makeText(context, R.string.clip_sync_test_no_address, Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        ClipSyncService.testPush(
                                            address,
                                            clipPrefs.username.getValue(),
                                            clipPrefs.password.getValue(),
                                            context.getString(R.string.clip_sync_test_push_payload),
                                        )
                                    }
                                }
                            val message =
                                result.fold(
                                    { context.getString(R.string.clip_sync_test_push_ok) },
                                    { context.getString(R.string.clip_sync_test_connection_fail, it.message ?: it.toString()) },
                                )
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                    true
                }
            }
        screen.addPreference(testConnection)
        screen.addPreference(testPush)
    }

    companion object {
        private const val STORAGE_KEY = "clip_sync_storage_row"
        private const val TEST_CONNECTION_KEY = "clip_sync_test_connection_row"
        private const val TEST_PUSH_KEY = "clip_sync_test_push_row"
    }
}
