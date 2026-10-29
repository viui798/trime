/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.RimeDataLayout
import com.osfans.trime.data.importer.RimeResourceImporter
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.theme.ThemeFilesManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            R.string.theme_diagnostics,
            R.string.theme_diagnostics_summary,
        ) {
            startActivity<ThemeDiagnosticsActivity>()
        }
    }

    /** 芝版同文：外观页右下角「+」，直接选文件导入皮肤（与方案页的导入 FAB 同款交互）。 */
    private val skinImportPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            importSkins(uris)
        }

    /**
     * 导入皮肤：zip/单文件落到「皮肤/<包名>/」→ 摊平到「运行数据」→ 自动切到新皮肤。
     * 皮肤是应用层资源，不需要 Rime 部署。流程与向导的皮肤页一致。
     */
    private fun importSkins(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            ctx.toast(R.string.setup__import_busy)
            val result =
                runCatching {
                    RimeResourceImporter.import(ctx, uris, RimeDataLayout.themeDir, flat = false)
                }.getOrElse {
                    ctx.toast(R.string.setup__import_failed)
                    return@launch
                }
            if (result.isEmpty) {
                ctx.toast(R.string.setup__import_empty)
                return@launch
            }
            RimeDataLayout.mirror()
            // 一个皮肤包常带多个变体，取名字最短的当基础款并直接切换
            ThemeFilesManager
                .listThemes(DataManager.userDataDir)
                .minByOrNull { it.configId.length }
                ?.let { ThemeManager.prefs.selectedTheme.setValue(it.configId) }
            ctx.toast(
                if (result.skipped > 0) {
                    ctx.getString(R.string.setup__import_partial, result.files, result.skipped)
                } else {
                    ctx.getString(R.string.setup__import_ok, result.files)
                },
            )
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val fab =
            FloatingActionButton(requireContext()).apply {
                setImageResource(R.drawable.ic_baseline_add_24)
                imageTintList =
                    ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), android.R.color.white),
                    )
            }
        fab.setOnClickListener {
            runCatching { skinImportPicker.launch(arrayOf("*/*")) }
                .onFailure { requireContext().toast(R.string.setup__import_no_picker) }
        }
        (view as ViewGroup).addView(
            fab,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                marginEnd = (16 * density).toInt()
                bottomMargin = (16 * density).toInt()
            },
        )
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
