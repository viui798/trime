/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.font

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ColorManager
import splitties.dimensions.dp

/**
 * 「字体与字号」里的一行：标题是用途，摘要显示当前字体与字号。
 *
 * 点开是一个对话框，有序字体回退列表和字号（滑条）一起改——
 * 和「小狐狸」那种一行一用途的交互对齐，不用在两个页面之间来回跳。
 */
class FontScopePreference(
    context: Context,
    private val scope: FontOverrides.Scope,
) : Preference(context) {
    init {
        key = scope.fontKey
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(scope.titleRes)
        setOnPreferenceClickListener {
            showDialog()
            true
        }
    }

    fun refresh() {
        summary = buildSummary()
    }

    private fun buildSummary(): CharSequence {
        val ctx = context
        val fonts = FontOverrides.fontList(scope)
        val themeFont = themeFontName(scope)
        val fontText = when {
            fonts.isNotEmpty() -> fonts.joinToString(" → ")
            themeFont != null -> ctx.getString(R.string.font_follow_theme_named, themeFont)
            else -> ctx.getString(R.string.font_follow_theme)
        }
        if (!scope.supportsSize) return fontText
        val size = FontOverrides.sizeSp(scope)
        val themeSize = themeSize(scope)
        val sizeText = when {
            size > 0 -> ctx.getString(R.string.font_size_value, size)
            themeSize != null -> ctx.getString(R.string.font_size_follow_theme_named, themeSize)
            else -> ctx.getString(R.string.font_size_follow_theme)
        }
        return "$fontText · $sizeText"
    }

    /** 皮肤在这条用途上给的字号，用来显示「跟随主题（18 sp）」这个基准。 */
    private fun themeSize(scope: FontOverrides.Scope): Int? = runCatching {
        val theme = ThemeManager.activeTheme
        val style = theme.style
        when (scope) {
            FontOverrides.Scope.KEY -> style.keyTextSize
            FontOverrides.Scope.SYMBOL -> style.symbolTextSize.takeIf { it > 0 } ?: style.keyTextSize
            FontOverrides.Scope.CANDIDATE -> style.candidateTextSize
            FontOverrides.Scope.COMMENT -> style.commentTextSize
            FontOverrides.Scope.POPUP -> style.popupTextSize
            FontOverrides.Scope.PREEDIT -> theme.preedit.foreground.fontSize
            FontOverrides.Scope.FALLBACK -> 0f
        }.toInt().takeIf { it > 0 }
    }.getOrNull()

    /** 当前主题在这条用途上用的字体文件名，用来显示「跟随主题（xxx.ttf）」。 */
    private fun themeFontName(scope: FontOverrides.Scope): String? = runCatching {
        scope.themeFontKey?.let { key ->
            ColorManager.fontNames(ThemeManager.activeTheme, key)?.let { names ->
                return@runCatching names.joinToString(" → ").takeIf { it.isNotBlank() }
            }
        }
        val style = ThemeManager.activeTheme.style
        when (scope) {
            FontOverrides.Scope.KEY -> style.keyFont
            FontOverrides.Scope.SYMBOL -> style.symbolFont
            FontOverrides.Scope.CANDIDATE -> style.candidateFont
            FontOverrides.Scope.COMMENT -> style.commentFont
            FontOverrides.Scope.POPUP -> style.popupFont
            FontOverrides.Scope.PREEDIT -> style.textFont
            FontOverrides.Scope.FALLBACK -> style.hanbFont
        }.toList().joinToString(" → ").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun showDialog() {
        val ctx = context
        val catalog = runCatching { FontOverrides.availableFonts() }.getOrDefault(emptyList())
        val currentFonts = FontOverrides.fontList(scope)
        val currentSize = FontOverrides.sizeSp(scope)

        val density = ctx.resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val selected = currentFonts.toMutableList()
        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        fun renderRows() {
            rows.removeAllViews()
            if (selected.isEmpty()) rows.addView(TextView(ctx).apply {
                setText(R.string.font_follow_theme)
            })
            selected.forEachIndexed { index, name ->
                val row = LinearLayout(ctx)
                row.addView(TextView(ctx).apply { text = name }, LinearLayout.LayoutParams(0, -2, 1f))
                listOf("↑", "↓", "×").forEachIndexed { action, label ->
                    row.addView(android.widget.Button(ctx).apply {
                        text = label
                        contentDescription = ctx.getString(when (action) {
                            0 -> R.string.font_move_up
                            1 -> R.string.font_move_down
                            else -> R.string.font_remove
                        }, name)
                        isEnabled = when (action) {
                            0 -> index > 0
                            1 -> index < selected.lastIndex
                            else -> true
                        }
                        setOnClickListener {
                            when (action) {
                                0 -> java.util.Collections.swap(selected, index, index - 1)
                                1 -> java.util.Collections.swap(selected, index, index + 1)
                                else -> selected.removeAt(index)
                            }
                            renderRows()
                        }
                    }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
                }
                rows.addView(row)
            }
            rows.addView(android.widget.Button(ctx).apply {
                setText(R.string.font_add)
                setOnClickListener {
                    val choices = (catalog + selected).distinct()
                    val checked = BooleanArray(choices.size) { choices[it] in selected }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.font_add)
                        .setMultiChoiceItems(choices.toTypedArray(), checked) { _, index, value -> checked[index] = value }
                        .setPositiveButton(R.string.ok) { _, _ ->
                            selected.removeAll { name -> !checked[choices.indexOf(name)] }
                            choices.forEachIndexed { i, name -> if (checked[i] && name !in selected) selected.add(name) }
                            renderRows()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
        renderRows()

        val themeSize = themeSize(scope)
        fun sizeText(value: Int): String = when {
            value > 0 -> ctx.getString(R.string.font_size_value, value)
            themeSize != null -> ctx.getString(R.string.font_size_follow_theme_named, themeSize)
            else -> ctx.getString(R.string.font_size_follow_theme)
        }

        val sizeLabel = TextView(ctx).apply {
            text = sizeText(currentSize)
            setPadding(padding, (16 * density).toInt(), padding, 0)
        }
        val sizeBar = SeekBar(ctx).apply {
            max = MAX_SIZE_SP
            progress = currentSize
            setPadding(padding, 0, padding, 0)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        sizeLabel.text = sizeText(value)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }

        val scroll = ScrollView(ctx).apply {
            addView(
                rows,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ctx.dp(280),
                ),
            )
            if (catalog.isEmpty()) {
                addView(
                    TextView(ctx).apply {
                        text = ctx.getString(R.string.font_empty_catalog)
                        setPadding(padding, (8 * density).toInt(), padding, 0)
                    },
                )
            }
            if (scope.supportsSize) {
                addView(sizeLabel)
                addView(sizeBar)
            }
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(scope.titleRes)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                FontOverrides.setFontList(scope, selected)
                if (scope.supportsSize) {
                    FontOverrides.setSizeSp(scope, sizeBar.progress)
                }
                refresh()

            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.font_follow_theme) { _, _ ->
                FontOverrides.reset(scope)
                refresh()

            }
            .create()
        dialog.show()
    }

    private companion object {
        const val MAX_SIZE_SP = 60
    }
}
