/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.RimeProfileManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ThemeManager {
    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    /**
     * 内置底座主题（`shared/trime.yaml`）：键盘布局、按键行为的最终兜底。
     * 它不作为可选皮肤出现在主题列表里，但文件必须留着——所有主题都读不出来时靠它。
     */
    const val BUILTIN_THEME_ID = "trime"

    /** 芝版同文默认外观：用户导入的「咖啡」皮肤 + 「咖啡·简•◎」（简白）配色。 */
    const val DEFAULT_THEME_ID = "咖啡.trime"
    const val DEFAULT_COLOR_ID = "kfa03"

    private const val PREF_DEFAULT_THEME_MIGRATED = "default_theme_coffee_v1"

    /**
     * @param includeBuiltin 是否包含内置底座主题。
     *   给用户挑主题的列表传 `false`（不显示「同文输入法芝士」那一项）；
     *   兜底查找必须用 `true`，否则一个主题都读不出来时没有可回退的。
     */
    fun getAllThemes(includeBuiltin: Boolean = true): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val dirs = linkedSetOf(DataManager.userDataDir)
        // 直连方案包 / 方案库模式下，皮肤被摊平进当前方案目录而不是「运行数据」，
        // 只扫「运行数据」会漏掉全部皮肤（新装后直接进直连时列表里只剩内置主题）。
        if (RimeProfileManager.isProfile()) dirs += RimeProfileManager.currentDir()
        val userThemes = dirs.flatMap { ThemeFilesManager.listThemes(it) }
        return (sharedThemes + userThemes)
            .distinctBy { it.configId }
            .filter { includeBuiltin || it.configId != BUILTIN_THEME_ID }
    }

    private lateinit var _activeTheme: Theme

    private var _activeFindings: List<ThemeDiagnostics.Finding>? = null

    /**
     * What static checks found in the active theme, or null when it was read
     * from its deployed artifact and never checked. Serves the diagnostics
     * screen: the findings belong to the load that produced the active theme.
     */
    val activeFindings: List<ThemeDiagnostics.Finding>?
        get() {
            ensureActiveTheme()
            return _activeFindings
        }

    private fun ensureActiveTheme() {
        if (!::_activeTheme.isInitialized) {
            _activeTheme = evaluateActiveTheme()
        }
    }

    var activeTheme: Theme
        get() {
            ensureActiveTheme()
            return _activeTheme
        }
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
        val findings: List<ThemeDiagnostics.Finding>?,
    )

    private fun getThemeById(id: String): ResolvedTheme {
        loadThemeOrNull(id)?.let { return it }

        // 全新安装 / 清数据后，内置资源可能还没同步到 shared 目录，此刻所有主题
        // 都会读失败。先补一次同步再试一次，别让用户在这里看到「主题加载失败」。
        if (runCatching { DataManager.sync() }.isSuccess) {
            loadThemeOrNull(id)?.let { return it }
        }

        if (id != "trime") {
            loadThemeOrNull("trime")?.let {
                Timber.w("Theme '$id' is unavailable, fallback to default theme 'trime'")
                return it
            }
        }

        var lastFailure: ThemeLoader.ThemeLoadError? = null
        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            when (val result = ThemeLoader.loadTheme(fallbackId)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to available theme '$fallbackId'")
                    return ResolvedTheme(fallbackId, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> lastFailure = result.error
            }
        }

        // 一个都读不出来时，如果已经有一套主题在生效就继续用它——切换失败顶多是
        // 没切换成功，不该把整个应用带崩（原先这里直接 error()，用户看到的是闪退）。
        if (::_activeTheme.isInitialized) {
            Timber.w(lastFailure, "No theme could be loaded, keeping the active one")
            return ResolvedTheme(prefs.selectedTheme.getValue(), _activeTheme, _activeFindings)
        }

        Timber.w(lastFailure, "No valid theme available")
        error("No valid theme available")
    }

    private fun loadThemeOrNull(id: String): ResolvedTheme? =
        when (val result = ThemeLoader.loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> ResolvedTheme(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> {
                Timber.w(result.error)
                null
            }
        }

    private fun evaluateActiveTheme(): Theme {
        val selectedThemeId = prefs.selectedTheme.getValue()
        val resolvedTheme = getThemeById(selectedThemeId)
        val newTheme = resolvedTheme.theme
        if (resolvedTheme.configId != selectedThemeId) {
            prefs.selectedTheme.setValue(resolvedTheme.configId)
        }
        applyTheme(resolvedTheme)
        return newTheme
    }

    private fun applyTheme(resolvedTheme: ResolvedTheme) {
        val theme = resolvedTheme.theme
        // The findings describe the file this load read; they are not tied to the
        // views, so they are refreshed even when the theme itself is unchanged.
        _activeFindings = resolvedTheme.findings
        // 皮肤资源（backgrounds/fonts）从「皮肤/<包>/」直读；必须在
        // ColorManager.attachTheme 之前切好，否则第一帧会去找旧目录。
        ThemeAssets.select(resolvedTheme.configId)
        // A structurally equal theme suppresses the change notification below, so the
        // UI tree keeps its views and their injected scope. Replace neither the
        // caches nor the scope in that case, or later scheme changes would update
        // the new global scope while existing views still read the old one.
        if (::_activeTheme.isInitialized && _activeTheme == theme) return
        ColorManager.attachTheme(theme)
        activeTheme = theme
    }

    fun init(configuration: Configuration) {
        migrateDefaultThemeIfNeeded()
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    /**
     * 一次性迁移：把默认主题从内置底座换成「咖啡」，配色换成「咖啡·简•◎」（简白）。
     *
     * 默认值只在首次读取 pref 时生效，已经装过并选过主题的设备不会自动变，
     * 所以这里补一次迁移。只有设备上确实存在咖啡皮肤时才切——否则会把用户
     * 锁在一个加载不出来的主题上。
     */
    private fun migrateDefaultThemeIfNeeded() {
        val sp = prefs.selectedTheme.sharedPreferences
        if (sp.getBoolean(PREF_DEFAULT_THEME_MIGRATED, false)) return
        sp.edit { putBoolean(PREF_DEFAULT_THEME_MIGRATED, true) }
        if (prefs.selectedTheme.getValue() != BUILTIN_THEME_ID) return
        val hasCoffee =
            runCatching {
                getAllThemes(includeBuiltin = false).any { it.configId == DEFAULT_THEME_ID }
            }.getOrDefault(false)
        if (!hasCoffee) {
            Timber.i("默认主题迁移：设备上还没有咖啡皮肤，保持内置主题")
            return
        }
        Timber.i("默认主题迁移：内置主题 → %s，配色 → %s", DEFAULT_THEME_ID, DEFAULT_COLOR_ID)
        prefs.selectedTheme.setValue(DEFAULT_THEME_ID)
        prefs.normalModeColor.setValue(DEFAULT_COLOR_ID)
    }

    /**
     * Switches to theme [configId], falling back when it is unavailable.
     * Loading runs on [Dispatchers.IO]; state changes and listener callbacks run on the main thread.
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    suspend fun selectTheme(configId: String): String {
        val resolvedTheme = withContext(Dispatchers.IO) { getThemeById(configId) }
        return withContext(Dispatchers.Main.immediate) {
            applyTheme(resolvedTheme)
            prefs.selectedTheme.setValue(resolvedTheme.configId)
            resolvedTheme.configId
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.preferenceDelegates.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
}
