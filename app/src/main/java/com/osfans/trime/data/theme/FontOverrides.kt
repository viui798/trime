/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File

/**
 * 芝版同文：把字体从皮肤里剥出来。
 *
 * 皮肤（`.trime.yaml`）里的 `style` 字体声明和字号是作者定死的，用户想换个
 * 字体只能去改 yaml。这里另存一套「按用途」的覆盖值，优先级高于主题：
 *
 * - 字体：逗号分隔的文件名列表（和 trime.yaml 里写法一致，可写多个做回退）
 * - 字号：整数 sp，`0` 表示跟随主题
 *
 * 读的地方有三处：[ThemeFonts]（取 Typeface）、[KeyboardView][com.osfans.trime.ime.keyboard.KeyboardView]
 * 等控件（取字号）。改动后调用 [ThemeManager.notifyRenderingChanged]，主题监听会重建输入视图。
 */
object FontOverrides {
    /**
     * @param fontKey 偏好键（字体）
     * @param sizeKey 偏好键（字号），为空表示该用途不提供字号
     * @param themeFontKey 对应的 `style` 字体键（如 key_font），[ThemeFonts] 按它查覆盖
     */
    enum class Scope(
        val fontKey: String,
        val sizeKey: String,
        val themeFontKey: String?,
        val titleRes: Int,
        val supportsSize: Boolean,
    ) {
        KEY(
            "font_override_key",
            "font_size_override_key",
            "key_font",
            R.string.font_scope_key,
            true,
        ),
        SYMBOL(
            "font_override_symbol",
            "font_size_override_symbol",
            "symbol_font",
            R.string.font_scope_symbol,
            true,
        ),
        CANDIDATE(
            "font_override_candidate",
            "font_size_override_candidate",
            "candidate_font",
            R.string.font_scope_candidate,
            true,
        ),
        COMMENT(
            "font_override_comment",
            "font_size_override_comment",
            "comment_font",
            R.string.font_scope_comment,
            true,
        ),
        POPUP(
            "font_override_popup",
            "font_size_override_popup",
            "popup_font",
            R.string.font_scope_popup,
            true,
        ),
        PREEDIT(
            "font_override_preedit",
            "font_size_override_preedit",
            "text_font",
            R.string.font_scope_preedit,
            true,
        ),
        FALLBACK(
            "font_override_fallback",
            "",
            "hanb_font",
            R.string.font_scope_fallback,
            false,
        ),
        WORD_CLOUD(
            "font_override_word_cloud",
            "",
            null,
            R.string.font_scope_word_cloud,
            false,
        ),
        ;

        companion object {
            private val byThemeKey = entries
                .mapNotNull { scope -> scope.themeFontKey?.let { it to scope } }
                .toMap()

            fun ofThemeKey(key: String): Scope? = byThemeKey[key]
        }
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }

    /**
     * 覆盖值的代次。[ThemeFonts] 的 Typeface 缓存按它失效——
     * 字体换了但代次没动，键盘会一直用旧的 Typeface。
     */
    @Volatile
    var generation: Long = 0L
        private set

    private val listener by lazy {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            if (!key.startsWith(FONT_PREFIX) && !key.startsWith(SIZE_PREFIX)) return@OnSharedPreferenceChangeListener
            generation++
            Timber.d("FontOverrides changed: $key -> generation=$generation")
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private const val FONT_PREFIX = "font_override_"
    private const val SIZE_PREFIX = "font_size_override_"
    private const val SEPARATOR = ","

    // 编辑值和生效值分开，避免保存文字样式后在下一次键盘重建时提前生效。
    private fun appliedFonts(scope: Scope): List<String> {
        val raw = prefs.getString("applied_${scope.fontKey}", null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun applyPending() {
        prefs.edit(commit = true) {
            Scope.entries.forEach { scope ->
                putString("applied_${scope.fontKey}", org.json.JSONArray(fontList(scope)).toString())
                if (scope.supportsSize) putInt("applied_${scope.sizeKey}", sizeSp(scope))
            }
        }
        generation++
    }

    fun removeFont(name: String) {
        Scope.entries.forEach { scope ->
            setFontList(scope, fontList(scope).filterNot { it == name })
            val applied = appliedFonts(scope).filterNot { it == name }
            prefs.edit { putString("applied_${scope.fontKey}", org.json.JSONArray(applied).toString()) }
        }
        generation++
    }

    fun fontList(scope: Scope): List<String> {
        val raw = prefs.getString(scope.fontKey, "").orEmpty()
        return runCatching {
            val array = org.json.JSONArray(raw)
            List(array.length()) { array.getString(it) }
        }.getOrElse { raw.split(SEPARATOR) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(32)
    }

    fun setFontList(scope: Scope, names: List<String>) {
        val normalized = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(32)
        prefs.edit { putString(scope.fontKey, org.json.JSONArray(normalized).toString()) }
    }

    /** 0 表示跟随主题。 */
    fun sizeSp(scope: Scope): Int =
        if (!scope.supportsSize) 0 else prefs.getInt(scope.sizeKey, 0)

    fun setSizeSp(scope: Scope, sp: Int) {
        if (!scope.supportsSize) return
        prefs.edit { putInt(scope.sizeKey, sp.coerceIn(0, 60)) }
    }

    fun reset(scope: Scope) {
        prefs.edit {
            remove(scope.fontKey)
            if (scope.supportsSize) remove(scope.sizeKey)
        }
    }

    fun resetAll() {
        prefs.edit {
            Scope.entries.forEach {
                remove(it.fontKey)
                if (it.supportsSize) remove(it.sizeKey)
            }
        }
    }

    fun hasAnyOverride(): Boolean = Scope.entries.any {
        fontList(it).isNotEmpty() || (it.supportsSize && sizeSp(it) > 0)
    }

    /** [ThemeFonts] 用：有覆盖就返回覆盖的字体文件，否则 null（跟随主题）。 */
    fun fontsForThemeKey(themeFontKey: String): List<String>? {
        val scope = Scope.ofThemeKey(themeFontKey) ?: return null
        return appliedFonts(scope).takeIf { it.isNotEmpty() }
    }

    /** 控件用：有覆盖字号就用它，否则用主题给的值。 */
    fun sizeOrNull(scope: Scope): Float? =
        (if (scope.supportsSize) prefs.getInt("applied_${scope.sizeKey}", 0) else 0)
            .takeIf { it > 0 }?.toFloat()

    /**
     * 字体目录里现有的字体文件。
     *
     * 顺序即优先级：皮肤包 → 运行数据（用户目录）→ 内置。
     */
    fun availableFonts(): List<String> {
        val dirs = buildList {
            ThemeAssets.fontsDir()?.let(::add)
            add(File(DataManager.userDataDir, "fonts"))
            add(File(DataManager.sharedDataDir, "fonts"))
            add(File(DataManager.userDataDir, "Fonts"))
            add(File(DataManager.sharedDataDir, "Fonts"))
        }
        val seen = linkedSetOf<String>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .onEnter { !isSymbolicLink(it) }
                .filter { it.isFile && !isSymbolicLink(it) && it.extension.lowercase() in FONT_EXTENSIONS }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .sortedBy { it.lowercase() }
                .forEach { seen.add(it) }
        }
        return seen.toList()
    }

    fun isSymbolicLink(file: File): Boolean = runCatching {
        android.system.OsConstants.S_ISLNK(android.system.Os.lstat(file.path).st_mode)
    }.getOrDefault(true)

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
}
