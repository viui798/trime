/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.osfans.trime.R
import com.osfans.trime.ui.common.ImportFab
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.RimeDataLayout
import com.osfans.trime.data.importer.RimeResourceImporter
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import splitties.resources.styledColor

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                ThemePickerDialog.build(lifecycleScope, requireContext()).show()
                updateSummaries()
            }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                ColorPickerDialog.build(lifecycleScope, requireContext()).show()
                updateSummaries()
            }
            true
        }
        updateSummaries()
    }

    /**
     * 主题/配色的切换是在对话框里异步完成的，点完对话框立刻读 pref 还是旧值，
     * 小字会一直停在旧名字上（看起来像「切换没生效」）。这里监听变化再刷新。
     */
    private val onThemeChanged = ThemeManager.OnThemeChangeListener {
        view?.post { updateSummaries() }
    }

    private val onColorChanged = ColorManager.OnColorChangeListener {
        view?.post { updateSummaries() }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.addOnChangedListener(onThemeChanged)
        ColorManager.addOnChangedListener(onColorChanged)
        updateSummaries()
    }

    override fun onPause() {
        ThemeManager.removeOnChangedListener(onThemeChanged)
        ColorManager.removeOnChangedListener(onColorChanged)
        super.onPause()
    }

    /** 芝版同文：「主题」「配色」下面的小字显示当前正在使用的主题/配色名（而非「选取主题/配色」占位）。 */
    private fun updateSummaries() {
        val themeName = currentThemeName()
        findPreference<Preference>("selected_theme")?.summary =
            themeName ?: getString(R.string.selected_theme_summary)
        val colorName =
            runCatching {
                ThemeManager.activeTheme.presetColorSchemes.entries
                    .firstOrNull { it.value == ColorManager.activeColorScheme }
                    ?.value?.get("name")
                    ?: ColorManager.activeColorScheme["name"]
            }.getOrNull()
        findPreference<Preference>("normal_mode_color")?.summary =
            colorName?.takeIf { it.isNotBlank() } ?: getString(R.string.normal_mode_color_summary)
    }

    private fun currentThemeName(): String? {
        val id = ThemeManager.prefs.selectedTheme.getValue()
        return runCatching { ThemeManager.getAllThemes().firstOrNull { it.configId == id }?.name }.getOrNull()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            R.string.theme_diagnostics,
            R.string.theme_diagnostics_summary,
        ) {
            startActivity<ThemeDiagnosticsActivity>()
        }
    }

    /** 外观页右下角「+」的导入入口（由快捷菜单的「导入」调用）。 */
    private val skinImportPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            importSkins(uris)
        }

    /**
     * 导入皮肤：zip/单文件落到「皮肤/<包名>/」→ 摊平到「运行数据」。
     * 芝版同文：导入后不自动切换主题，提示导入成功即可，需在「主题」里选用或「部署」才生效。
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
            // 与方案导入一致：走完整 prepare()，顺带把方案包的预编译词典对齐到「部署」。
            RimeDataLayout.prepare()
            ctx.toast(
                if (result.skipped > 0) {
                    ctx.getString(R.string.setup__import_partial, result.files, result.skipped)
                } else {
                    ctx.getString(R.string.setup__import_ok, result.files)
                },
            )
            updateSummaries()
        }
    }

    /** 「部署」：重新应用当前主题，让导入/改动的皮肤与配色生效。 */
    private fun deployTheme() {
        viewLifecycleOwner.lifecycleScope.launch {
            val id = ThemeManager.prefs.selectedTheme.getValue()
            runCatching { ThemeManager.selectTheme(id) }
            requireContext().toast(R.string.setup__deploy_ok)
            updateSummaries()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        // 芝版同文：样式与方案页的加号完全一致，见 ImportFab。
        val fab =
            FloatingActionButton(requireContext()).also { ImportFab.decorate(it) }
        fab.setOnClickListener { showQuickMenu(fab) }
        // 芝版同文：FAB 要挂在 RecyclerView 的容器（FrameLayout #list_container）里才能
        // 悬浮在右下角；PreferenceFragment 的根是 LinearLayout，直接 addView 会被排到末尾。
        val container: ViewGroup =
            view.findViewById(android.R.id.list_container)
                ?: (view as ViewGroup).getChildAt(0) as ViewGroup
        container.addView(
            fab,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                marginEnd = (ImportFab.MARGIN_DP * density).toInt()
                bottomMargin = (ImportFab.MARGIN_DP * density).toInt()
            },
        )
    }

    /** 芝版同文：外观页右下角「+」弹出「导入 / 部署」快捷菜单（与方案页同款交互）。 */
    private fun showQuickMenu(anchor: View) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var popup: PopupWindow? = null
        val menu =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(
                    quickButton(R.drawable.ic_baseline_add_24, R.string.schema_action_import) {
                        popup?.dismiss()
                        runCatching { skinImportPicker.launch(arrayOf("*/*")) }
                            .onFailure { ctx.toast(R.string.setup__import_no_picker) }
                    },
                )
                addView(
                    quickButton(R.drawable.ic_baseline_refresh_reversed_24, R.string.schema_action_deploy) {
                        popup?.dismiss()
                        deployTheme()
                    },
                )
            }
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup =
            PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = dp(8).toFloat()
            }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        popup.showAtLocation(
            anchor.rootView,
            Gravity.NO_GRAVITY,
            location[0] + anchor.width - menu.measuredWidth,
            location[1] - menu.measuredHeight - dp(8),
        )
    }

    private fun quickButton(
        icon: Int,
        label: Int,
        onClick: () -> Unit,
    ): MaterialButton {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return MaterialButton(ctx).apply {
            setIconResource(icon)
            iconTint = android.content.res.ColorStateList.valueOf(styledColor(com.google.android.material.R.attr.colorOnSurface))
            setText(label)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = dp(2)
            iconSize = dp(24)
            cornerRadius = dp(18)
            insetTop = dp(10)
            insetBottom = dp(10)
            minWidth = dp(76)
            minHeight = dp(76)
            isAllCaps = false
            textSize = 12f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    styledColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                )
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
            setOnClickListener { onClick() }
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
