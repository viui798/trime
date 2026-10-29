/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.buildDocumentsProviderIntent
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.toast
import io.github.whiredplanck.storageaccess.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileSettingsFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val prefs = AppPrefs.defaultInstance().profile
    private var dataStorageMode by prefs.dataStorageMode
    private val externalRimeDir by prefs.externalRimeTreeUri
    private val backgroundSyncEnable = prefs.periodicBackgroundSync
    private val lastSyncTime by prefs.lastBackgroundSyncTime
    private val lastSyncStatus by prefs.lastBackgroundSyncStatus

    /** Set while the picker runs as part of switching the storage mode to external sync. */
    private var pendingExternalSyncSetup = false

    private val onBackgroundSyncEnable = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        editSyncIntervalPreference.isEnabled = v
    }

    private val onSyncIntervalChange =
        PreferenceDelegate.OnChangeListener<Int> { _, _ ->
            if (backgroundSyncEnable.getValue()) {
                viewModel.restartBackgroundSyncWork.value = true
            }
        }

    private val onDataPathChange = PreferenceDelegate.OnChangeListener<String> { _, _ ->
        updateDataPathSummary()
    }

    private val onStorageModeChange =
        PreferenceDelegate.OnChangeListener<DataStorageMode> { _, _ ->
            updateStorageModeUi()
        }

    private lateinit var dataStorageModePreference: ListPreference
    private lateinit var dataPathPreference: Preference
    private lateinit var engineRootPreference: Preference

    private lateinit var editSyncIntervalPreference: EditTextIntPreference

    private val storageAccess = StorageAccess(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.periodicBackgroundSync.registerOnChangeListener(onBackgroundSyncEnable)
        prefs.periodicBackgroundSyncInterval.registerOnChangeListener(onSyncIntervalChange)
        prefs.externalRimeTreeUri.registerOnChangeListener(onDataPathChange)
        prefs.dataStorageMode.registerOnChangeListener(onStorageModeChange)
    }

    private fun provideDataPathSummary(): String? {
        if (externalRimeDir.isEmpty()) return null
        val dirUri = Uri.parse(externalRimeDir)
        val docId = DocumentsContract.getTreeDocumentId(dirUri)
        return if (docId.contains(':')) {
            val (volume, rel) = docId.split(':', limit = 2)
            when (volume) {
                "raw" -> rel
                "primary" -> "/$rel"
                else -> "/storage/$volume/$rel"
            }
        } else {
            docId
        }.removePrefix("/storage/emulated/0")
    }

    private fun updateDataPathSummary() {
        dataPathPreference.summary = provideDataPathSummary()
    }

    // -------------------------------------------------- 外部引擎数据目录

    private fun provideEngineRootSummary(): String =
        if (DataManager.isUsingExternalRoot()) {
            DataManager.rimeRootDir.absolutePath
        } else {
            getString(R.string.engine_root_summary_internal)
        }

    /**
     * 把引擎数据目录指到外部存储。
     *
     * 必须先拿到「所有文件访问」：librime 是原生命令层，只认真实文件路径，
     * SAF 给的 content:// URI 它读不了，所以这件事没法用普通存储授权替代。
     * 切换时把 方案/皮肤/学习 复制过去；部署产物与运行数据会由引擎重建。
     */
    private fun onEngineRootClicked() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            ctx.toast(R.string.engine_root_permission_needed)
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                )
            runCatching { startActivity(intent) }
                .onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            return
        }
        lifecycleScope.launch {
            val picked = storageAccess.pickDirectory() ?: return@launch
            val target = absolutePathOf(picked.uri)
            if (target == null) {
                ctx.toast(R.string.setup__data_path_import_failed)
                return@launch
            }
            val oldRoot = DataManager.rimeRootDir
            withLoadingDialog(ctx, R.string.engine_root_migrating) {
                withContext(Dispatchers.IO) {
                    val newRoot = File(target)
                    newRoot.mkdirs()
                    MIGRATED_DIRS.forEach { name ->
                        val src = File(oldRoot, name)
                        val dst = File(newRoot, name)
                        if (src.isDirectory && runCatching { src.canonicalPath != dst.canonicalPath }.getOrDefault(true)) {
                            copyDirectory(src, dst)
                        }
                    }
                }
                prefs.externalEngineRoot.setValue(target)
                // 引擎目录已经在外部了，就不需要再走「从外部存储同步」那条 SAF 通道，
                // 两者叠加会互相覆盖；统一用应用存储模式，避免 isStorageAvailable 误判。
                prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
                DataManager.invalidateRimeRootCache()
            }
            engineRootPreference.summary = provideEngineRootSummary()
            ctx.toast(R.string.engine_root_switched)
        }
    }

    /** SAF 的 tree uri → 绝对路径（只处理本机 primary / 其它卷）。 */
    private fun absolutePathOf(uri: Uri): String? =
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (!docId.contains(':')) return@runCatching null
            val (volume, rel) = docId.split(':', limit = 2)
            when (volume) {
                "raw" -> rel
                "primary" -> "/storage/emulated/0/$rel"
                else -> "/storage/$volume/$rel"
            }
        }.getOrNull()

    private fun copyDirectory(
        src: File,
        dst: File,
    ) {
        dst.mkdirs()
        src.listFiles()?.forEach { child ->
            val out = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectory(child, out)
            } else {
                runCatching { child.copyTo(out, overwrite = true) }
            }
        }
    }

    private fun updateStorageModeUi() {
        dataStorageModePreference.value = dataStorageMode.name
        dataPathPreference.isEnabled = dataStorageMode == DataStorageMode.EXTERNAL_SYNC
    }

    private fun pickDataPath() {
        lifecycleScope.launch {
            val picked = storageAccess.pickDirectory()
            if (picked == null) {
                // Cancelling the picker never nags: the current folder stays as it is, and a
                // storage mode switch the user did not finish setting up is undone silently.
                if (pendingExternalSyncSetup) {
                    pendingExternalSyncSetup = false
                    cancelExternalSyncSetup()
                }
                return@launch
            }
            val ctx = requireContext()
            val externalSyncSetup = pendingExternalSyncSetup
            pendingExternalSyncSetup = false
            lifecycleScope.launch {
                withLoadingDialog(ctx) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            UserDataManager.persistTreeUri(picked.uri)
                            viewModel.rime.runOnReady { deploy() }
                        }
                    }.onSuccess {
                        updateDataPathSummary()
                        ctx.toast(R.string.setup__data_path_imported)
                    }.onFailure {
                        if (externalSyncSetup) {
                            fallbackToAppStorage()
                        } else {
                            // Keep the picked folder: it stays the user's choice, and a folder
                            // that keeps failing makes the deploy path fall back on its own.
                            updateDataPathSummary()
                            ctx.toast(R.string.setup__data_path_import_failed)
                        }
                    }
                }
            }
        }
    }

    private fun promptSelectAnotherDirectory() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.select_another_directory_to_sync)
            .setPositiveButton(R.string.select_another_directory) { _, _ ->
                // The current folder stays in place until the user picks another one.
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptExternalSyncFolderSelection() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.external_sync_select_folder_message)
            .setPositiveButton(R.string.setup__select_data_path) { _, _ ->
                pendingExternalSyncSetup = true
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelExternalSyncSetup()
            }.setOnCancelListener {
                cancelExternalSyncSetup()
            }.show()
    }

    /** Falls back to app-specific storage when the user gives up on choosing a folder. */
    private fun cancelExternalSyncSetup() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        updateStorageModeUi()
    }

    private fun fallbackToAppStorage() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        updateStorageModeUi()
        updateDataPathSummary()
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.external_sync_fallback_app_storage)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.storage) {
                isIconSpaceReserved = false
                val storageModes = DataStorageMode.entries
                addPreference(
                    MaterialListPreference(ctx).apply {
                        dataStorageModePreference = this
                        key = AppPrefs.Profile.DATA_STORAGE_MODE
                        isIconSpaceReserved = false
                        setTitle(R.string.data_storage_mode)
                        entries = storageModes.map { getString(it.stringRes) }.toTypedArray()
                        entryValues = storageModes.map { it.name }.toTypedArray()
                        value = dataStorageMode.name
                        setDefaultValue(DataStorageMode.APP_STORAGE.name)
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setOnPreferenceChangeListener { _, newValue ->
                            val oldMode = prefs.dataStorageMode.getValue()
                            val mode =
                                DataStorageMode.valueOf(newValue as String)
                            prefs.dataStorageMode.setValue(mode)
                            if (
                                oldMode == DataStorageMode.APP_STORAGE &&
                                mode == DataStorageMode.EXTERNAL_SYNC &&
                                !UserDataManager.hasExternalAccess()
                            ) {
                                promptExternalSyncFolderSelection()
                            } else if (
                                oldMode == DataStorageMode.EXTERNAL_SYNC &&
                                mode == DataStorageMode.APP_STORAGE
                            ) {
                                // The picked folder and its grant are kept, so switching back to
                                // external sync does not ask the user to pick it again.
                                updateDataPathSummary()
                            }
                            true
                        }
                    },
                )
                addPreference(
                    Preference(ctx).apply {
                        dataPathPreference = this
                        key = AppPrefs.Profile.EXTERNAL_RIME_TREE_URI
                        isIconSpaceReserved = false
                        setTitle(R.string.user_data_dir)
                        summary = provideDataPathSummary()
                        setOnPreferenceClickListener {
                            promptSelectAnotherDirectory()
                            true
                        }
                    },
                )
                addPreference(
                    Preference(ctx).apply {
                        engineRootPreference = this
                        key = AppPrefs.Profile.EXTERNAL_ENGINE_ROOT
                        isIconSpaceReserved = false
                        setTitle(R.string.engine_root_title)
                        summary = provideEngineRootSummary()
                        setOnPreferenceClickListener {
                            onEngineRootClicked()
                            true
                        }
                    },
                )
            }
            addCategory(R.string.synchronization) {
                isIconSpaceReserved = false
                addPreference(R.string.sync_user_data_immediately) {
                    lifecycleScope.launch {
                        withLoadingDialog(ctx) {
                            runCatching {
                                viewModel.rime.runOnReady { syncUserData() }
                            }.onSuccess { success ->
                                ctx.toast(
                                    when {
                                        !success -> R.string.sync_user_data_failure

                                        UserDataManager.useExternalSync() ->
                                            R.string.sync_user_data_success_external

                                        else -> R.string.sync_user_data_success
                                    },
                                )
                            }.onFailure {
                                ctx.toast(R.string.sync_user_data_failure)
                            }
                        }
                    }
                }
                addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = AppPrefs.Profile.PERIODIC_BACKGROUND_SYNC
                        isIconSpaceReserved = false
                        setTitle(R.string.periodic_background_sync)
                        setDefaultValue(false)
                        summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> {
                            if (backgroundSyncEnable.getValue()) {
                                val lastTime: String
                                val lastStatus: String
                                if (lastSyncTime != 0L) {
                                    lastTime = customFormatTimeInDefault("yyyy-MM-dd HH:mm", lastSyncTime)
                                    lastStatus = getString(if (lastSyncStatus) R.string.success else R.string.failure)
                                } else {
                                    lastTime = "N/A"
                                    lastStatus = "N/A"
                                }
                                getString(
                                    R.string.periodic_background_sync_status,
                                    lastTime,
                                    lastStatus,
                                )
                            } else {
                                ""
                            }
                        }
                    },
                )
                addPreference(
                    EditTextIntPreference(ctx).apply {
                        editSyncIntervalPreference = this
                        key = AppPrefs.Profile.PERIODIC_BACKGROUND_SYNC_INTERVAL
                        isIconSpaceReserved = false
                        setTitle(R.string.periodic_background_sync_interval)
                        min = 15
                        setDefaultValue(30)
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        isEnabled = backgroundSyncEnable.getValue()
                    },
                )
            }
            addCategory(R.string.maintenance) {
                isIconSpaceReserved = false
                addPreference(
                    title = getString(R.string.browse_app_data_dir),
                    // 文件浏览器打开的是应用 external files 根目录，rime/ 就在里面。
                    summary = DataManager.rimeRootDir.absolutePath,
                ) {
                    runCatching {
                        ctx.startActivity(buildDocumentsProviderIntent())
                    }.onFailure {
                        ctx.toast(R.string.browse_app_data_dir_failed)
                    }
                }
                addPreference(R.string.reset, R.string.reset_hint) {
                    val items = ctx.assets.list("shared") ?: return@addPreference
                    val checked = BooleanArray(items.size) { false }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.reset)
                        .setMultiChoiceItems(items, checked) { _, id, isChecked ->
                            checked[id] = isChecked
                        }.setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            lifecycleScope.launch {
                                var res = true
                                withLoadingDialog(ctx) {
                                    withContext(Dispatchers.IO) {
                                        res =
                                            items
                                                .filterIndexed { index, _ -> checked[index] }
                                                .fold(true) { acc, asset ->
                                                    val destPath =
                                                        DataManager.sharedDataDir.resolve(asset).absolutePath
                                                    ResourceUtils
                                                        .copyFile("shared/$asset", destPath)
                                                        .fold({ acc and true }, { acc and false })
                                                }
                                    }
                                }
                                ctx.toast((if (res) R.string.reset_success else R.string.reset_failure))
                            }
                        }.show()
                }
            }
        }
        updateStorageModeUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.periodicBackgroundSync.unregisterOnChangeListener(onBackgroundSyncEnable)
        prefs.periodicBackgroundSyncInterval.unregisterOnChangeListener(onSyncIntervalChange)
        prefs.externalRimeTreeUri.unregisterOnChangeListener(onDataPathChange)
        prefs.dataStorageMode.unregisterOnChangeListener(onStorageModeChange)
    }

    override fun onResume() {
        super.onResume()
        updateStorageModeUi()
        val ctx = requireContext()
        if (UserDataManager.useExternalSync() && !UserDataManager.hasExternalAccess()) {
            ctx.toast(R.string.data_path_permission_revoked)
        }
    }

    companion object {
        /** 切换引擎目录时搬过去的东西：用户自己的内容。部署产物与运行数据由引擎重建。 */
        private val MIGRATED_DIRS = listOf("方案", "皮肤", "学习")
    }
}
