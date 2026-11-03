/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.font

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.osfans.trime.ui.common.ImportFab
import splitties.resources.styledColor
import androidx.preference.PreferenceCategory
import android.graphics.Typeface
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.data.DataManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.preference.Preference
import com.osfans.trime.R
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeAssets
import com.osfans.trime.ui.common.PaddingPreferenceFragment

/**
 * 「字体与字号」：把皮肤里定死的字体拆出来，按用途给用户单独设置。
 *
 * 优先级：这里的设置 > 配色里的字体列表 > 皮肤 style 里的字体声明。
 * 字号填 0（滑条拖到最左）表示跟随皮肤。
 */
class FontSettingsFragment : PaddingPreferenceFragment() {
    private var deploying = false
    private val scopePreferences = mutableListOf<FontScopePreference>()
    private val importFonts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: error("Missing filename")
                        require(name == File(name).name && name != "." && name != "..")
                        require(File(name).extension.lowercase() in setOf("ttf", "otf", "ttc"))
                        val directory = File(DataManager.userDataDir, "fonts")
                        check(directory.isDirectory || directory.mkdirs())
                        val temp = File.createTempFile("font-", ".tmp", directory)
                        try {
                            resolver.openInputStream(uri)?.use { input ->
                                temp.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= 64L * 1024 * 1024)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            } ?: error("Cannot read font")
                            Typeface.createFromFile(temp)
                            var destination = File(directory, name)
                            var suffix = 1
                            while (destination.exists() || ThemeAssets.resolveFont(destination.name) != null) {
                                destination = File(directory, "${File(name).nameWithoutExtension}-${suffix++}.${File(name).extension}")
                            }
                            check(temp.renameTo(destination))
                        } finally {
                            temp.delete()
                        }
                    }.isSuccess
                }
            }
            if (isAdded) {
                Toast.makeText(context, getString(R.string.font_import_result, success, uris.size - success), Toast.LENGTH_LONG).show()
                refreshFontCatalog()
                scopePreferences.forEach { it.refresh() }
            }
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        scopePreferences.clear()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(Preference(ctx).apply {
                key = "font_catalog"
                setTitle(R.string.font_catalog)
                setSummary(R.string.font_manage_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    androidx.navigation.fragment.NavHostFragment.findNavController(this@FontSettingsFragment)
                        .navigate(com.osfans.trime.ui.main.NavigationRoute.FontFiles)
                    true
                }
            })
            addPreference(PreferenceCategory(ctx).apply {
                setTitle(R.string.font_text_style)
                isIconSpaceReserved = false
            })
            addPreference(Preference(ctx).apply {
                isIconSpaceReserved = false
                isSelectable = false
                setSummary(R.string.font_deploy_hint)
            })
            FontOverrides.Scope.entries.forEach { scope ->
                val item = FontScopePreference(ctx, scope).apply { refresh() }
                scopePreferences += item
                addPreference(item)
            }
            addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    setTitle(R.string.font_reset)
                    summary = ctx.getString(R.string.font_reset_summary)
                    setOnPreferenceClickListener {
                        FontOverrides.resetAll()
                        scopePreferences.forEach { it.refresh() }

                        true
                    }
                },
            )
        }
    }

    private fun refreshFontCatalog() {
        // 字体列表只在独立管理页读取，避免每次进入文字样式都遍历文件。
    }

    private fun deployFonts() {
        if (deploying) return
        deploying = true
        lifecycleScope.launch {
            val result = runCatching {
                FontOverrides.applyPending()
                ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue())
            }
            deploying = false
            if (isAdded) {
                Toast.makeText(requireContext(), if (result.isSuccess) R.string.setup__deploy_ok else R.string.setup__deploy_failed, Toast.LENGTH_LONG).show()
                refreshFontCatalog()
                scopePreferences.forEach { it.refresh() }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val fab = FloatingActionButton(requireContext()).also { ImportFab.decorate(it) }
        fab.contentDescription = getString(R.string.font_actions)
        fab.setOnClickListener { showQuickMenu(fab) }
        val container: ViewGroup = view.findViewById(android.R.id.list_container)
            ?: (view as ViewGroup).getChildAt(0) as ViewGroup
        container.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = (ImportFab.MARGIN_DP * density).toInt()
            bottomMargin = (ImportFab.MARGIN_DP * density).toInt()
        })
        listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, (104 * density).toInt())
        refreshFontCatalog()
    }

    override fun onResume() {
        super.onResume()
        // 从其它页面回来时，字体目录可能刚导入过新字体，重算一遍摘要。
        refreshFontCatalog()
        scopePreferences.forEach { it.refresh() }
    }
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
                        runCatching { importFonts.launch(arrayOf("*/*")) }
                            .onFailure { Toast.makeText(ctx, R.string.setup__import_no_picker, Toast.LENGTH_LONG).show() }
                    },
                )
                addView(
                    quickButton(R.drawable.ic_baseline_refresh_reversed_24, R.string.schema_action_deploy) {
                        popup?.dismiss()
                        deployFonts()
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

}
