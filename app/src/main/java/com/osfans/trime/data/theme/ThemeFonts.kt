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
        ColorManager.fontNames(theme, key) ?: fallback

    val hanb: Typeface
        get() = getTypeface(names("hanb_font", style.hanbFont.toList()))

    val latin: Typeface
        get() = getTypeface(names("latin_font", style.latinFont.toList()))

    val candidate: Typeface
        get() = getTypeface(names("candidate_font", style.candidateFont.toList()))

    val comment: Typeface
        get() = getTypeface(names("comment_font", style.commentFont.toList()))

    val key: Typeface
        get() = getTypeface(names("key_font", style.keyFont.toList()))

    val label: Typeface
        get() = getTypeface(names("label_font", style.labelFont.toList()))

    val popup: Typeface
        get() = getTypeface(names("popup_font", style.popupFont.toList()))

    val symbol: Typeface
        get() = getTypeface(names("symbol_font", style.symbolFont.toList()))

    val text: Typeface
        get() = getTypeface(names("text_font", style.textFont.toList()))

    val toolbar: Typeface
        get() = getTypeface(theme.toolBar.buttonFont.toList())

    private fun makeFileStateList(names: List<String>): List<FileState> = names.asSequence()
        // 皮肤包里的 fonts 直读，其次才是「运行数据」和 shared。
        .mapNotNull { ThemeAssets.resolveFont(it) }
        .map { FileState(it.path, it.lastModified(), it.length()) }
        .toList()

    private fun resolveKey(fonts: List<String>): TypefaceKey {
        val existing = makeFileStateList(fonts)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && existing.isNotEmpty()) {
            TypefaceKey(makeFileStateList(names("latin_font", style.latinFont.toList()) + fonts + names("hanb_font", style.hanbFont.toList())))
        } else {
            TypefaceKey(existing.take(1))
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
                val fontFamilies = key.files.map { getFontFamily(it) }
                Typeface.CustomFallbackBuilder(fontFamilies.first())
                    .apply {
                        for (i in 1 until fontFamilies.size) {
                            addCustomFallback(fontFamilies[i])
                        }
                        setSystemFallback("sans-serif")
                    }
                    .build()
            } else {
                Typeface.createFromFile(key.files.first().path)
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
