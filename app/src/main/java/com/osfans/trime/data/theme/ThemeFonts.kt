/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.File

class ThemeFonts(private val theme: Theme) {
    private val style = theme.style

    private data class FileState(
        val path: String,
        val lastModified: Long,
        val size: Long,
    )

    private data class TypefaceKey(val files: List<FileState>)

    private val fontFamilyCache =
        object : androidx.collection.LruCache<FileState, FontFamily>(16) {}

    private val typefaceCache =
        object : androidx.collection.LruCache<TypefaceKey, Typeface>(16 * 1024) {
            override fun sizeOf(key: TypefaceKey, value: Typeface): Int = key.files.sumOf {
                (it.size / 1024).toInt().coerceAtLeast(1)
            }
        }

    private fun names(key: String, fallback: List<String>): List<String> =
        // 芝版同文：设置里的「字体与字号」优先于皮肤声明，其次才是配色里的字体列表。
        FontOverrides.fontsForThemeKey(key)
            ?: ColorManager.fontNames(theme, key)
            ?: fallback

    /**
     * 具名字体的强缓存。
     *
     * 绘制路径（`KeyView.onDraw`）每帧都要取一次 `fonts.key`，而解析一次要 stat
     * 两三组字体文件再拼 LruCache 的 key——中文字体动辄十几兆，按 KB 计的 LruCache
     * 容量根本装不下，条目当场被淘汰，于是每帧都在重建 Typeface
     * （`minikin::FontCollection::init`）。40 多个按键叠起来就是几十毫秒的卡顿。
     *
     * 这里按字体名做一层实例级强缓存；配色切换（[ColorManager.colorGeneration]
     * 变化）时整体失效，因为配色可以覆盖字体列表。
     */
    private val typefaceByName = HashMap<String, Typeface>()
    private var typefaceByNameGeneration = -1L
    private var typefaceByFontGeneration = -1L

    private fun cached(
        name: String,
        fallback: List<String>,
    ): Typeface {
        val generation = ColorManager.colorGeneration
        // 设置里改了字体也要整体失效，否则键盘会一直用旧 Typeface。
        val fontGeneration = FontOverrides.generation
        if (generation != typefaceByNameGeneration || fontGeneration != typefaceByFontGeneration) {
            typefaceByName.clear()
            typefaceByNameGeneration = generation
            typefaceByFontGeneration = fontGeneration
        }
        return typefaceByName.getOrPut(name) { getTypeface(names(name, fallback)) }
    }

    val hanb: Typeface
        get() = cached("hanb_font", style.hanbFont.toList())

    val latin: Typeface
        get() = cached("latin_font", style.latinFont.toList())

    val candidate: Typeface
        get() = cached("candidate_font", style.candidateFont.toList())

    val comment: Typeface
        get() = cached("comment_font", style.commentFont.toList())

    val key: Typeface
        get() = cached("key_font", style.keyFont.toList())

    val label: Typeface
        get() = cached("label_font", style.labelFont.toList())

    val popup: Typeface
        get() = cached("popup_font", style.popupFont.toList())

    val symbol: Typeface
        get() = cached("symbol_font", style.symbolFont.toList())

    val text: Typeface
        get() = cached("text_font", style.textFont.toList())

    private var cachedToolbar: Typeface? = null

    val toolbar: Typeface
        get() = cachedToolbar ?: getTypeface(theme.toolBar.buttonFont.toList()).also { cachedToolbar = it }

    private fun makeFileStateList(names: List<String>): List<FileState> = names.asSequence()
        // 皮肤包里的 fonts 直读，其次才是「运行数据」和 shared。
        .mapNotNull { ThemeAssets.resolveFont(it) }
        .map { FileState(it.path, it.lastModified(), it.length()) }
        .distinctBy { it.path }
        .toList()

    private fun resolveKey(fonts: List<String>): TypefaceKey {
        val existing = makeFileStateList(fonts)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && existing.isNotEmpty()) {
            TypefaceKey(makeFileStateList(fonts + names("latin_font", style.latinFont.toList()) + names("hanb_font", style.hanbFont.toList())))
        } else {
            TypefaceKey(existing)
        }
    }

    private fun getTypeface(fonts: List<String>): Typeface {
        val key = resolveKey(fonts)
        val typeface = typefaceCache[key]
        return if (typeface == null) {
            Timber.d("getTypeface: key=$key")
            val newTypeface = if (key.files.isEmpty()) {
                Typeface.DEFAULT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fontFamilies = key.files.mapNotNull { state ->
                    runCatching { getFontFamily(state) }.onFailure { Timber.w(it, "Invalid font: %s", state.path) }.getOrNull()
                }.take(Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount())
                if (fontFamilies.isEmpty()) return Typeface.DEFAULT.also { typefaceCache.put(key, it) }
                Typeface.CustomFallbackBuilder(fontFamilies.first())
                    .apply {
                        for (i in 1 until fontFamilies.size) {
                            addCustomFallback(fontFamilies[i])
                        }
                        setSystemFallback("sans-serif")
                    }
                    .build()
            } else {
                key.files.firstNotNullOfOrNull { runCatching { Typeface.createFromFile(it.path) }.getOrNull() } ?: Typeface.DEFAULT
            }
            typefaceCache.put(key, newTypeface)
            newTypeface
        } else {
            typeface
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getFontFamily(state: FileState): FontFamily {
        val family = fontFamilyCache[state]
        return if (family == null) {
            val newFamily = FontFamily.Builder(
                Font.Builder(File(state.path)).build(),
            ).build()
            fontFamilyCache.put(state, newFamily)
            newFamily
        } else {
            family
        }
    }
}
