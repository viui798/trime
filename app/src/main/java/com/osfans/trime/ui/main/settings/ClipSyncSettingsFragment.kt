/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.ClipSyncService
import com.osfans.trime.data.clipsync.StoragePathUtils
import com.osfans.trime.data.clipsync.SyncFilterPrefs
import com.osfans.trime.data.clipsync.SyncServerPreferences
import com.osfans.trime.data.clipsync.network.SyncClient
import com.osfans.trime.data.clipsync.network.SyncClient.ServerBackend
import com.osfans.trime.data.clipsync.service.QuickSyncTileService
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.navigateWithAnim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板同步设置页（完整移植自中州鹅 clipboardsync/ui/SettingsActivity.kt 的
 * SettingsFragment，行集与 preferences_local.xml 一致）：
 * 开启同步 / 服务端 / 同步账户 / 同步间隔 / 同步过滤 / 同步新截屏 /
 * 存储位置 / 测试连接 / 测试推送。
 */
class ClipSyncSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipSync) {
    private val clipPrefs get() = AppPrefs.defaultInstance().clipSync

    private data class ServerProfile(
        val key: String,
        val label: String,
    )

    private val profiles by lazy {
        listOf(
            ServerProfile("oneclip", getString(R.string.clip_sync_profile_oneclip)),
            ServerProfile("clipcascade", getString(R.string.clip_sync_profile_clipcascade)),
            ServerProfile("syncclipboard", getString(R.string.clip_sync_profile_syncclipboard)),
            ServerProfile("custom", getString(R.string.clip_sync_profile_custom)),
        )
    }

    private val storagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val visiblePath = StoragePathUtils.visiblePathFromUriString(uri.toString()) ?: uri.toString()
            val prefs = preferenceManager.sharedPreferences ?: return@registerForActivityResult
            prefs
                .edit()
                .putString(AppPrefs.ClipSync.DOWNLOAD_PATH, visiblePath)
                .putString(AppPrefs.ClipSync.DOWNLOAD_PATH_URI, uri.toString())
                .apply()
            updateDownloadPathSummary()
        }

    private val openTestPushDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            testPushSelectedUri = uri
            updateTestPushSelectedFileSummary()
        }

    private val screenshotPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val prefs = preferenceManager.sharedPreferences ?: return@registerForActivityResult
            if (granted) {
                prefs.edit().putBoolean(AppPrefs.ClipSync.SCREENSHOT_SYNC, true).apply()
                findPreference<SwitchPreferenceCompat>(AppPrefs.ClipSync.SCREENSHOT_SYNC)?.isChecked = true
                ClipSyncService.startSyncService(requireContext(), "screenshot-sync-enabled")
            } else {
                prefs.edit().putBoolean(AppPrefs.ClipSync.SCREENSHOT_SYNC, false).apply()
                findPreference<SwitchPreferenceCompat>(AppPrefs.ClipSync.SCREENSHOT_SYNC)?.isChecked = false
                Toast.makeText(requireContext(), R.string.clip_sync_permission_needed, Toast.LENGTH_SHORT).show()
            }
        }

    private var pendingProgressDialog: AlertDialog? = null
    private var testPushDialog: AlertDialog? = null
    private var testPushTextEdit: EditText? = null
    private var testPushSelectedFileView: TextView? = null
    private var testPushSelectedUri: Uri? = null

    private val settingActionKeys =
        listOf(
            KEY_SERVER_PROFILE,
            KEY_SYNC_ACCOUNT,
            KEY_SYNC_FILTER,
            KEY_DOWNLOAD_PATH,
            KEY_TEST_CONNECTION,
            KEY_TEST_PUSH,
        )

    private val quickSyncPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != AppPrefs.ClipSync.ENABLED && key != AppPrefs.ClipSync.UNREACHABLE) {
                return@OnSharedPreferenceChangeListener
            }
            syncQuickSyncSwitchState(prefs)
            context?.let { QuickSyncTileService.requestTileRefresh(it) }
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val prefs = preferenceManager.sharedPreferences ?: return
        SyncServerPreferences.migrate(prefs)
        SyncFilterPrefs.ensureDefaults(prefs)

        // 行序与上游 preferences_local.xml 一致：开关 / 服务端 / 账户 / 间隔 / 过滤 /
        // 截屏 / 存储 / 测试连接 / 测试推送。
        buildQuickSyncRow(screen, prefs)
        buildServerProfileRow(screen, prefs)
        buildSyncAccountRow(screen)
        buildSyncIntervalRow(screen, prefs)
        buildSyncFilterRow(screen)
        buildScreenshotSyncRow(screen, prefs)
        buildDownloadPathRow(screen, prefs)
        buildTestConnectionRow(screen)
        buildTestPushRow(screen)

        syncQuickSyncSwitchState(prefs)
    }

    override fun onStart() {
        super.onStart()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(quickSyncPreferenceListener)
    }

    override fun onStop() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(quickSyncPreferenceListener)
        super.onStop()
    }

    override fun onDestroyView() {
        // A long test can outlive this view; dismiss so the dialog does not keep the
        // destroyed Activity alive (see A4).
        pendingProgressDialog?.dismiss()
        pendingProgressDialog = null
        testPushDialog?.dismiss()
        testPushDialog = null
        super.onDestroyView()
    }

    // ---------- 行构建 ----------

    private fun buildQuickSyncRow(
        screen: PreferenceScreen,
        prefs: SharedPreferences,
    ) {
        val pref =
            SwitchPreferenceCompat(screen.context).apply {
                key = AppPrefs.ClipSync.ENABLED
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_enabled)
                setSummary(R.string.clip_sync_enabled_hint)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        ClipSyncService.startSyncService(requireContext(), "settings-enable", forceEnableSync = true)
                    } else if (newValue == false) {
                        preferenceManager.sharedPreferences?.edit()
                            ?.putBoolean(AppPrefs.ClipSync.UNREACHABLE, false)
                            ?.apply()
                        ClipSyncService.stopSyncService(requireContext())
                    }
                    true
                }
            }
        screen.addPreference(pref)
        syncQuickSyncSwitchState(prefs)
    }

    private fun buildServerProfileRow(
        screen: PreferenceScreen,
        prefs: SharedPreferences,
    ) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_SERVER_PROFILE
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_server_profile_title)
                setSummary(R.string.clip_sync_server_profile_summary)
                setOnPreferenceClickListener {
                    showServerProfileDialog()
                    true
                }
            },
        )
        updateServerProfileSummary(prefs)
    }

    private fun buildSyncAccountRow(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_SYNC_ACCOUNT
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_sync_account_title)
                setOnPreferenceClickListener {
                    showSyncAccountDialog()
                    true
                }
            },
        )
        updateCredentialPreferenceSummary()
    }

    private fun buildSyncIntervalRow(
        screen: PreferenceScreen,
        prefs: SharedPreferences,
    ) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_SYNC_INTERVAL
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_interval)
                summary = formatIntervalSummary(prefs)
                setOnPreferenceClickListener {
                    showSyncIntervalDialog()
                    true
                }
            },
        )
    }

    private fun buildSyncFilterRow(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_SYNC_FILTER
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_filter_title)
                setOnPreferenceClickListener {
                    findNavController().navigateWithAnim(NavigationRoute.ClipSyncFilter)
                    true
                }
            },
        )
        updateSyncFilterSummary()
    }

    private fun buildScreenshotSyncRow(
        screen: PreferenceScreen,
        prefs: SharedPreferences,
    ) {
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = AppPrefs.ClipSync.SCREENSHOT_SYNC
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_screenshot)
                setSummary(R.string.clip_sync_screenshot_hint)
                isChecked = prefs.getBoolean(AppPrefs.ClipSync.SCREENSHOT_SYNC, false)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && !hasScreenshotImagePermission()) {
                        screenshotPermissionLauncher.launch(screenshotImagePermission())
                        false
                    } else {
                        if (newValue == true) {
                            ClipSyncService.startSyncService(requireContext(), "screenshot-sync-enabled")
                        }
                        true
                    }
                }
            },
        )
    }

    private fun buildDownloadPathRow(
        screen: PreferenceScreen,
        prefs: SharedPreferences,
    ) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_DOWNLOAD_PATH
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_storage)
                setOnPreferenceClickListener {
                    showStorageLocationDialog()
                    true
                }
            },
        )
        updateDownloadPathSummary()
    }

    private fun buildTestConnectionRow(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_TEST_CONNECTION
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_test_connection)
                setSummary(R.string.clip_sync_test_connection_hint)
                setOnPreferenceClickListener {
                    testConnection()
                    true
                }
            },
        )
    }

    private fun buildTestPushRow(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                key = KEY_TEST_PUSH
                isPersistent = false
                isIconSpaceReserved = false
                setTitle(R.string.clip_sync_test_push)
                setSummary(R.string.clip_sync_test_push_hint)
                setOnPreferenceClickListener {
                    showTestPushDialog()
                    true
                }
            },
        )
    }

    // ---------- 行刷新 ----------

    private fun syncQuickSyncSwitchState(prefs: SharedPreferences?) {
        val sharedPrefs = prefs ?: return
        val enabled = sharedPrefs.getBoolean(AppPrefs.ClipSync.ENABLED, false)
        val unreachable = sharedPrefs.getBoolean(AppPrefs.ClipSync.UNREACHABLE, false)
        val preference = findPreference<SwitchPreferenceCompat>(AppPrefs.ClipSync.ENABLED) ?: return
        if (preference.isChecked != enabled) {
            preference.isChecked = enabled
        }
        preference.summary =
            if (enabled && unreachable) {
                getString(R.string.clip_sync_enabled_unreachable_hint)
            } else {
                getString(R.string.clip_sync_enabled_hint)
            }
        updateActionPreferencesEnabled(enabled)
    }

    private fun updateActionPreferencesEnabled(enabled: Boolean) {
        settingActionKeys.forEach { key ->
            findPreference<Preference>(key)?.isEnabled = enabled
        }
        findPreference<Preference>(AppPrefs.ClipSync.SCREENSHOT_SYNC)?.isEnabled = enabled
    }

    private fun updateServerProfileSummary(prefs: SharedPreferences) {
        val profileType =
            prefs.getString(AppPrefs.ClipSync.SERVER_PROFILE_TYPE, "syncclipboard")
                ?: "syncclipboard"
        val profile = profiles.firstOrNull { it.key == profileType } ?: profiles.first()
        val customAlias = prefs.getString(AppPrefs.ClipSync.SERVER_PROFILE_CUSTOM_ALIAS, "").orEmpty().trim()
        val alias = if (profile.key == "custom" && customAlias.isNotEmpty()) customAlias else profile.label
        val address = prefs.getString(AppPrefs.ClipSync.SERVER_ADDRESS, "").orEmpty().trim()
        findPreference<Preference>(KEY_SERVER_PROFILE)?.summary =
            if (address.isBlank()) alias else "$alias\n$address"
    }

    private fun updateCredentialPreferenceSummary() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val activeProfile = activeProfile(prefs)
        val username = storedUsernameForProfile(prefs, activeProfile)
        val password = storedPasswordForProfile(prefs, activeProfile)
        val usernameSummary = username.ifBlank { getString(R.string.clip_sync_not_set) }
        val passwordSummary =
            if (password.isBlank()) {
                getString(R.string.clip_sync_not_set)
            } else {
                "*".repeat(password.length.coerceAtMost(16))
            }
        findPreference<Preference>(KEY_SYNC_ACCOUNT)?.summary =
            getString(R.string.clip_sync_sync_account_summary, usernameSummary, passwordSummary)
    }

    private fun updateDownloadPathSummary() {
        val saved = preferenceManager.sharedPreferences?.getString(AppPrefs.ClipSync.DOWNLOAD_PATH, null)
        findPreference<Preference>(KEY_DOWNLOAD_PATH)?.summary =
            saved?.takeIf { it.isNotBlank() } ?: getString(R.string.clip_sync_storage_default)
    }

    private fun updateSyncFilterSummary() {
        val prefs = preferenceManager.sharedPreferences ?: return
        findPreference<Preference>(KEY_SYNC_FILTER)?.summary = SyncFilterPrefs.buildSummary(requireContext(), prefs)
    }

    // ---------- 服务端配置对话框 ----------

    private fun showServerProfileDialog() {
        val context = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val currentType = prefs.getString(AppPrefs.ClipSync.SERVER_PROFILE_TYPE, "syncclipboard") ?: "syncclipboard"
        val currentProfile = profiles.firstOrNull { it.key == currentType } ?: profiles.first()

        val padding = dp(20)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, dp(8), padding, 0)
            }

        container.addView(TextView(context).apply { setText(R.string.clip_sync_server_alias_title) })
        val aliasSpinner = Spinner(context)
        aliasSpinner.adapter =
            ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.label })
        aliasSpinner.setSelection(profiles.indexOfFirst { it.key == currentProfile.key }.coerceAtLeast(0))
        container.addView(
            aliasSpinner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        val customAliasEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_server_custom_alias_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                setText(prefs.getString(AppPrefs.ClipSync.SERVER_PROFILE_CUSTOM_ALIAS, ""))
            }
        container.addView(
            customAliasEdit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )

        container.addView(
            TextView(context).apply {
                setText(R.string.clip_sync_server_address)
                setPadding(0, dp(12), 0, 0)
            },
        )
        val serverAddressEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_server_address_hint)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setText(storedAddressForProfile(prefs, currentProfile.key))
            }
        container.addView(
            serverAddressEdit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        container.addView(
            TextView(context).apply {
                setText(R.string.clip_sync_server_transport_help)
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                setPadding(0, dp(8), 0, 0)
            },
        )

        fun updateFields(keepAddress: Boolean) {
            val selectedProfile = profiles[aliasSpinner.selectedItemPosition]
            val isCustom = selectedProfile.key == "custom"
            customAliasEdit.visibility = if (isCustom) View.VISIBLE else View.GONE
            if (!isCustom && (!keepAddress || serverAddressEdit.text.isNullOrBlank())) {
                serverAddressEdit.setText(storedAddressForProfile(prefs, selectedProfile.key))
            }
        }

        aliasSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    updateFields(keepAddress = false)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        updateFields(keepAddress = true)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.clip_sync_server_profile_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedProfile = profiles[aliasSpinner.selectedItemPosition]
                val customAlias = customAliasEdit.text?.toString()?.trim().orEmpty()
                val serverAddress = serverAddressEdit.text?.toString()?.trim().orEmpty()
                prefs
                    .edit()
                    .apply {
                        // 切换前把当前活动 profile 的账户存回它的 per-profile 键
                        saveActiveCredentialsForProfile(this, prefs, currentType)
                    }
                    .putString(AppPrefs.ClipSync.SERVER_PROFILE_TYPE, selectedProfile.key)
                    .putString(AppPrefs.ClipSync.SERVER_PROFILE_CUSTOM_ALIAS, customAlias)
                    .putString(AppPrefs.ClipSync.SERVER_ADDRESS, serverAddress)
                    .apply {
                        profileAddressKey(selectedProfile.key)?.let { putString(it, serverAddress) }
                        putString(AppPrefs.ClipSync.USERNAME, storedUsernameForProfile(prefs, selectedProfile.key))
                        putString(AppPrefs.ClipSync.PASSWORD, storedPasswordForProfile(prefs, selectedProfile.key))
                    }
                    .apply()
                updateServerProfileSummary(prefs)
                updateCredentialPreferenceSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSyncAccountDialog() {
        val context = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val activeProfile = activeProfile(prefs)

        val padding = dp(20)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, dp(8), padding, 0)
            }
        val usernameEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_username)
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
                setText(storedUsernameForProfile(prefs, activeProfile))
                setSelection(text?.length ?: 0)
            }
        container.addView(usernameEdit)

        val passwordEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
                transformationMethod = PasswordTransformationMethod.getInstance()
                setText(storedPasswordForProfile(prefs, activeProfile))
                setSelection(text?.length ?: 0)
            }
        container.addView(
            passwordEdit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )
        var passwordVisible = false
        val toggleButton =
            Button(context).apply {
                text = getString(R.string.clip_sync_password_show)
                setOnClickListener {
                    passwordVisible = !passwordVisible
                    passwordEdit.transformationMethod =
                        if (passwordVisible) {
                            HideReturnsTransformationMethod.getInstance()
                        } else {
                            PasswordTransformationMethod.getInstance()
                        }
                    text = getString(if (passwordVisible) R.string.clip_sync_password_hide else R.string.clip_sync_password_show)
                    passwordEdit.setSelection(passwordEdit.text?.length ?: 0)
                }
            }
        container.addView(
            toggleButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.clip_sync_sync_account_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUsername = usernameEdit.text?.toString().orEmpty()
                val newPassword = passwordEdit.text?.toString().orEmpty()
                prefs
                    .edit()
                    .apply {
                        profileUsernameKey(activeProfile)?.let { putString(it, newUsername) }
                        profilePasswordKey(activeProfile)?.let { putString(it, newPassword) }
                        putString(AppPrefs.ClipSync.USERNAME, newUsername)
                        putString(AppPrefs.ClipSync.PASSWORD, newPassword)
                    }
                    .apply()
                updateCredentialPreferenceSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSyncIntervalDialog() {
        val context = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val current = runCatching { prefs.getInt(AppPrefs.ClipSync.INTERVAL, 3) }.getOrDefault(3)
        val padding = dp(20)
        val input =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                setPadding(padding, padding / 2, padding, 0)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.clip_sync_interval)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString()?.toIntOrNull()?.coerceIn(1, 60) ?: current
                clipPrefs.interval.setValue(value)
                findPreference<Preference>(KEY_SYNC_INTERVAL)?.summary = formatIntervalSummary(prefs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 存储位置对话框 ----------

    private fun showStorageLocationDialog() {
        val context = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val padding = dp(20)
        val storagePathEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_storage_hint)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setText(prefs.getString(AppPrefs.ClipSync.DOWNLOAD_PATH, ""))
                setPadding(padding, padding / 2, padding, 0)
            }

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clip_sync_storage)
                .setView(storagePathEdit)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val input = storagePathEdit.text?.toString()?.trim().orEmpty()
                    val normalizedDisplay = StoragePathUtils.formatStoragePath(input).orEmpty()
                    val derivedUri = StoragePathUtils.derivePersistableUri(input)
                    prefs
                        .edit()
                        .putString(AppPrefs.ClipSync.DOWNLOAD_PATH, normalizedDisplay.ifBlank { input })
                        .putString(AppPrefs.ClipSync.DOWNLOAD_PATH_URI, derivedUri)
                        .apply()
                    updateDownloadPathSummary()
                }
                .setNeutralButton(R.string.clip_sync_storage_pick, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                runCatching { storagePicker.launch(null) }
            }
        }
        dialog.show()
    }

    // ---------- 测试 ----------

    private fun testConnection() {
        val prefs = preferenceManager.sharedPreferences ?: return
        if (!prefs.getBoolean(AppPrefs.ClipSync.ENABLED, false)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clip_sync_quick_sync_required_title)
                .setMessage(R.string.clip_sync_quick_sync_required_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val activeProfile = activeProfile(prefs)
        val address = prefs.getString(AppPrefs.ClipSync.SERVER_ADDRESS, "").orEmpty()
        val username = storedUsernameForProfile(prefs, activeProfile)
        val password = storedPasswordForProfile(prefs, activeProfile)
        val backend = ServerBackend.fromProfileType(activeProfile)

        if (address.isBlank()) {
            Toast.makeText(context, R.string.clip_sync_test_no_address, Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clip_sync_testing_connection)
                .setMessage(getString(R.string.clip_sync_connecting_to, address))
                .setCancelable(false)
                .create()
        progressDialog.show()
        pendingProgressDialog = progressDialog

        // viewLifecycleOwner, not a free CoroutineScope: connect+read timeouts total 40s, and
        // rotating or backing out during that window used to crash (see A4).
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SyncClient.testConnection(address, username, password, backend)
                }

            if (!isAdded) return@launch
            progressDialog.dismiss()
            pendingProgressDialog = null
            val ctx = context ?: return@launch
            if (result.isSuccess) {
                Toast.makeText(ctx, R.string.clip_sync_connection_success, Toast.LENGTH_SHORT).show()
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.clip_sync_connection_failed)
                    .setMessage(result.exceptionOrNull()?.message ?: getString(R.string.clip_sync_unknown_error))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showTestPushDialog() {
        val context = requireContext()
        val padding = dp(20)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, dp(8), padding, 0)
            }
        container.addView(
            TextView(context).apply { setText(R.string.clip_sync_test_push_hint) },
        )
        val textEdit =
            EditText(context).apply {
                hint = getString(R.string.clip_sync_test_push_text_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
        container.addView(
            textEdit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        val selectedFileView = TextView(context).apply { setText(R.string.clip_sync_test_push_no_file) }
        container.addView(
            selectedFileView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        val buttonsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pickButton =
            Button(context).apply {
                setText(R.string.clip_sync_test_push_pick_file)
                setOnClickListener { openTestPushDocument.launch(arrayOf("*/*")) }
            }
        val clearButton =
            Button(context).apply {
                setText(R.string.clip_sync_test_push_clear_file)
                setOnClickListener {
                    testPushSelectedUri = null
                    updateTestPushSelectedFileSummary()
                }
            }
        buttonsRow.addView(pickButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonsRow.addView(
            clearButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) },
        )
        container.addView(
            buttonsRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )

        testPushTextEdit = textEdit
        testPushSelectedFileView = selectedFileView
        updateTestPushSelectedFileSummary()

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clip_sync_test_push)
                .setView(container)
                .setPositiveButton(R.string.clip_sync_test_push_send, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                submitTestPush(dialog)
            }
        }
        dialog.setOnDismissListener {
            if (testPushDialog === dialog) {
                testPushDialog = null
                testPushTextEdit = null
                testPushSelectedFileView = null
                testPushSelectedUri = null
            }
        }

        testPushDialog = dialog
        dialog.show()
    }

    private fun updateTestPushSelectedFileSummary() {
        val targetView = testPushSelectedFileView ?: return
        val uri = testPushSelectedUri
        if (uri == null) {
            targetView.setText(R.string.clip_sync_test_push_no_file)
            return
        }
        val displayName = resolveDisplayName(uri) ?: uri.toString()
        targetView.text = getString(R.string.clip_sync_test_push_file_selected, displayName)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") {
            return uri.lastPathSegment?.substringAfterLast('/')
        }
        return runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        return@runCatching cursor.getString(index)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun submitTestPush(dialog: AlertDialog) {
        val prefs = preferenceManager.sharedPreferences ?: return
        val activeProfile = activeProfile(prefs)
        val address = prefs.getString(AppPrefs.ClipSync.SERVER_ADDRESS, "").orEmpty()
        val username = storedUsernameForProfile(prefs, activeProfile)
        val password = storedPasswordForProfile(prefs, activeProfile)
        val backend = ServerBackend.fromProfileType(activeProfile)

        if (address.isBlank()) {
            Toast.makeText(context, R.string.clip_sync_test_no_address, Toast.LENGTH_SHORT).show()
            return
        }

        val selectedUri = testPushSelectedUri
        val text = testPushTextEdit?.text?.toString()?.trim().orEmpty()
        val content = selectedUri?.toString() ?: text
        if (content.isBlank()) {
            Toast.makeText(context, R.string.clip_sync_test_push_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clip_sync_test_push_running)
                .setMessage(getString(R.string.clip_sync_connecting_to, address))
                .setCancelable(false)
                .create()
        progressDialog.show()
        pendingProgressDialog = progressDialog

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        SyncClient.putClipboard(
                            context = appContext,
                            serverUrl = address,
                            username = username,
                            pass = password,
                            backend = backend,
                            content = content,
                        )
                    }
                }

            if (!isAdded) return@launch
            progressDialog.dismiss()
            pendingProgressDialog = null
            val ctx = context ?: return@launch
            if (result.isSuccess) {
                Toast.makeText(ctx, R.string.clip_sync_test_push_ok, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.clip_sync_test_push_failed)
                    .setMessage(result.exceptionOrNull()?.message ?: getString(R.string.clip_sync_unknown_error))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    // ---------- profile 键位与凭据 ----------

    private fun activeProfile(prefs: SharedPreferences): String =
        prefs.getString(AppPrefs.ClipSync.SERVER_PROFILE_TYPE, "syncclipboard") ?: "syncclipboard"

    private fun profileAddressKey(profileKey: String): String? =
        when (profileKey) {
            "syncclipboard" -> AppPrefs.ClipSync.SERVER_ADDRESS_SYNCCLIPBOARD
            "oneclip" -> AppPrefs.ClipSync.SERVER_ADDRESS_ONECLIP
            "clipcascade" -> AppPrefs.ClipSync.SERVER_ADDRESS_CLIPCASCADE
            "custom" -> AppPrefs.ClipSync.SERVER_ADDRESS_CUSTOM
            else -> null
        }

    private fun profileUsernameKey(profileKey: String): String? =
        when (profileKey) {
            "syncclipboard" -> AppPrefs.ClipSync.USERNAME_SYNCCLIPBOARD
            "oneclip" -> AppPrefs.ClipSync.USERNAME_ONECLIP
            "clipcascade" -> AppPrefs.ClipSync.USERNAME_CLIPCASCADE
            "custom" -> AppPrefs.ClipSync.USERNAME_CUSTOM
            else -> null
        }

    private fun profilePasswordKey(profileKey: String): String? =
        when (profileKey) {
            "syncclipboard" -> AppPrefs.ClipSync.PASSWORD_SYNCCLIPBOARD
            "oneclip" -> AppPrefs.ClipSync.PASSWORD_ONECLIP
            "clipcascade" -> AppPrefs.ClipSync.PASSWORD_CLIPCASCADE
            "custom" -> AppPrefs.ClipSync.PASSWORD_CUSTOM
            else -> null
        }

    private fun storedAddressForProfile(
        prefs: SharedPreferences,
        profileKey: String,
    ): String = profileAddressKey(profileKey)?.let { prefs.getString(it, null)?.trim().orEmpty() }.orEmpty()

    private fun defaultUsernameForProfile(profileKey: String?): String = if (profileKey == "oneclip") "" else "admin"

    private fun defaultPasswordForProfile(profileKey: String?): String = if (profileKey == "oneclip") "" else "admin123"

    private fun storedUsernameForProfile(
        prefs: SharedPreferences,
        profileKey: String,
    ): String {
        val stored = profileUsernameKey(profileKey)?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
        return if (stored.isNotBlank() || profileKey == "oneclip") stored else defaultUsernameForProfile(profileKey)
    }

    private fun storedPasswordForProfile(
        prefs: SharedPreferences,
        profileKey: String,
    ): String {
        val stored = profilePasswordKey(profileKey)?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
        return if (stored.isNotBlank() || profileKey == "oneclip") stored else defaultPasswordForProfile(profileKey)
    }

    private fun saveActiveCredentialsForProfile(
        editor: SharedPreferences.Editor,
        prefs: SharedPreferences,
        profileKey: String?,
    ) {
        if (profileKey.isNullOrBlank()) return
        val username = prefs.getString(AppPrefs.ClipSync.USERNAME, null)
        val password = prefs.getString(AppPrefs.ClipSync.PASSWORD, null)
        profileUsernameKey(profileKey)?.let { key -> editor.putString(key, username.orEmpty()) }
        profilePasswordKey(profileKey)?.let { key -> editor.putString(key, password.orEmpty()) }
    }

    // ---------- 权限 ----------

    private fun screenshotImagePermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasScreenshotImagePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(requireContext(), screenshotImagePermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatIntervalSummary(prefs: SharedPreferences): String {
        val seconds = runCatching { prefs.getInt(AppPrefs.ClipSync.INTERVAL, 3) }.getOrDefault(3)
        return getString(R.string.clip_sync_interval_summary, seconds)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_SERVER_PROFILE = "clip_sync_server_profile_row"
        private const val KEY_SYNC_ACCOUNT = "clip_sync_sync_account_row"
        private const val KEY_SYNC_INTERVAL = "clip_sync_interval_row"
        private const val KEY_SYNC_FILTER = "clip_sync_filter_row"
        private const val KEY_DOWNLOAD_PATH = "clip_sync_storage_row"
        private const val KEY_TEST_CONNECTION = "clip_sync_test_connection_row"
        private const val KEY_TEST_PUSH = "clip_sync_test_push_row"
    }
}
