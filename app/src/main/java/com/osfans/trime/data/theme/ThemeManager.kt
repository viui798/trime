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

    fun getAllThemes(): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val dirs = linkedSetOf(DataManager.userDataDir)
        // 直连方案包 / 方案库模式下，皮肤被摊平进当前方案目录而不是「运行数据」，
        // 只扫「运行数据」会漏掉全部皮肤（新装后直接进直连时列表里只剩内置主题）。
        if (RimeProfileManager.isProfile()) dirs += RimeProfileManager.currentDir()
        val userThemes = dirs.flatMap { ThemeFilesManager.listThemes(it) }
        return (sharedThemes + userThemes).distinctBy { it.configId }
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
        ensureActiveTheme()
        ColorManager.init(configuration)
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
