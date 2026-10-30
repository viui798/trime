/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.osfans.trime.core.data.StorageLocation
import com.osfans.trime.core.data.ExternalRootAutoConfig
import com.osfans.trime.util.toast
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeFilesManager
import com.osfans.trime.databinding.FragmentSetupBinding
import com.osfans.trime.util.serializable

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding
    private var updatingStorageUi = false

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }

    private val prefs = AppPrefs.defaultInstance().profile

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater).apply {
            storageModeOptions.setOnCheckedChangeListener { _, checkedId ->
                if (updatingStorageUi) return@setOnCheckedChangeListener
                val newMode = when (checkedId) {
                    R.id.direct_external_option -> DataStorageMode.DIRECT_EXTERNAL
                    R.id.sync_from_external_option -> DataStorageMode.EXTERNAL_SYNC
                    R.id.app_specific_storage_option -> DataStorageMode.APP_STORAGE
                    else -> return@setOnCheckedChangeListener
                }
                if (newMode == DataStorageMode.DIRECT_EXTERNAL && !ExternalRootAutoConfig.permissionReady()) {
                    ExternalRootAutoConfig.openPermissionSettings(requireActivity())
                    sync()
                    return@setOnCheckedChangeListener
                }
                if (newMode == DataStorageMode.EXTERNAL_SYNC && !UserDataManager.hasExternalAccess()) {
                    (requireActivity() as SetupActivity).pickDataPath()
                    sync()
                    return@setOnCheckedChangeListener
                }
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { StorageLocation.switchTo(newMode) } }
                        .onFailure { requireContext().toast(it.message ?: "切换目录失败") }
                    sync()
                    (requireActivity() as SetupActivity).updateButtons()
                }
            }
            directExternalDesc.setOnClickListener { directExternalOption.isChecked = true }
            syncFromExternalDesc.setOnClickListener { syncFromExternalOption.isChecked = true }
            appSpecificStorageDesc.setOnClickListener { appSpecificStorageOption.isChecked = true }
        }
        sync()
        return binding.root
    }

    // Called on window focus changed
    fun sync() {
        val done = page.isDone()
        val isStorageModePage = page == SetupPage.Mode
        val checkedId = when (prefs.dataStorageMode.getValue()) {
            DataStorageMode.EXTERNAL_SYNC -> R.id.sync_from_external_option
            DataStorageMode.APP_STORAGE -> R.id.app_specific_storage_option
            DataStorageMode.DIRECT_EXTERNAL -> R.id.direct_external_option
        }
        with(binding) {
            appIcon.setImageResource(page.getIconRes())
            appIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.settings_on_primary_container),
                )
            storageModeOptions.visibility = if (isStorageModePage) View.VISIBLE else View.GONE
            updatingStorageUi = true
            storageModeOptions.check(checkedId)
            updatingStorageUi = false

            stepText.text = page.getTitleText(requireContext())
            hintText.text = buildHint()

            // 选填页（皮肤）永远显示按钮，方便重复导入；必填页完成后收起按钮显示对勾
            val showActionButton = page.isOptional() || (!done && page.showActionButton())
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireActivity()) }

            val showDone = done && !page.isOptional()
            doneText.visibility = if (showDone) View.VISIBLE else View.GONE
            doneIcon.visibility = if (showDone) View.VISIBLE else View.GONE
        }
    }

    /** 已导入时在提示文案后面追加导入结果，让用户看到到底吃进去了什么。 */
    private fun buildHint(): CharSequence {
        val context = requireContext()
        val base = page.getHintText(context)
        val result = when (page) {
            SetupPage.Schema -> {
                val ids = UserDataManager.listSchemaIds()
                when {
                    ids.isEmpty() -> null
                    ids.size > SCHEMA_NAME_PREVIEW ->
                        context.getString(
                            R.string.setup__import_schema_done_more,
                            ids.take(SCHEMA_NAME_PREVIEW).joinToString("、"),
                            ids.size,
                        )
                    else -> context.getString(R.string.setup__import_schema_done, ids.joinToString("、"))
                }
            }
            SetupPage.Skin ->
                ThemeFilesManager.listThemes(DataManager.userDataDir)
                    .takeIf { it.isNotEmpty() }
                    ?.let { themes ->
                        context.getString(
                            R.string.setup__import_skin_done,
                            themes.joinToString("、") { it.name },
                        )
                    }
            else -> null
        } ?: return base
        return TextUtils.concat(base, "\n\n", result)
    }

    companion object {
        /** 方案太多时只列前几个，避免提示文字撑爆页面。 */
        private const val SCHEMA_NAME_PREVIEW = 4
    }
}
