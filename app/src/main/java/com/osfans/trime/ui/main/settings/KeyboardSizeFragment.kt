/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.R
import com.osfans.trime.data.prefs.KeyboardSizePreview
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import splitties.dimensions.dp

abstract class KeyboardSizeFragment(private val safeArea: Boolean) : Fragment() {
    private var restorePreview: (() -> Unit)? = null
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewTask: Runnable? = null

    override fun onDestroyView() {
        previewTask?.let(previewHandler::removeCallbacks)
        previewTask = null
        restorePreview?.invoke()
        restorePreview = null
        super.onDestroyView()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val prefs = AppPrefs.defaultInstance().keyboard
        val preference = if (safeArea) prefs.bottomSafeArea else prefs.heightOverride
        val fontFollowPreference = if (safeArea) null else prefs.fontFollowsHeight
        val default = if (safeArea) -1 else 0
        var pending = preference.getValue()
        var pendingFontFollow = fontFollowPreference?.getValue() ?: false
        fun clearPreview() {
            val hadPreview =
                KeyboardSizePreview.heightDp != null ||
                    KeyboardSizePreview.safeAreaDp != null ||
                    KeyboardSizePreview.fontFollowHeight != null
            KeyboardSizePreview.clear()
            if (hadPreview) ThemeManager.notifyRenderingChanged()
        }
        restorePreview = { clearPreview() }
        fun applyPreview() {
            val current = if (safeArea) KeyboardSizePreview.safeArea() else KeyboardSizePreview.height()
            var changed = false
            if (current != pending) {
                if (safeArea) KeyboardSizePreview.safeAreaDp = pending else KeyboardSizePreview.heightDp = pending
                changed = true
            }
            if (!safeArea && KeyboardSizePreview.fontFollowHeight != pendingFontFollow) {
                KeyboardSizePreview.fontFollowHeight = pendingFontFollow
                changed = true
            }
            if (changed) ThemeManager.notifyRenderingChanged()
        }
        fun flushPreview() {
            previewTask?.let(previewHandler::removeCallbacks)
            previewTask = null
            applyPreview()
        }
        val themeHeight = runCatching { ThemeManager.activeTheme.style.keyboardHeight.toInt() }
            .getOrDefault(240).coerceIn(120, 500)
        val label = TextView(ctx).apply { textSize = 18f }
        fun refreshLabel() {
            label.text = when {
                pending == default -> getString(if (safeArea) R.string.keyboard_safe_auto else R.string.keyboard_height_theme)
                else -> getString(R.string.keyboard_dimension_dp, pending)
            }
        }
        val slider = SeekBar(ctx).apply {
            max = if (safeArea) 120 else 380
            progress = if (safeArea) pending.coerceAtLeast(0) else (pending.takeIf { it > 0 }
                ?: themeHeight).coerceIn(120, 500) - 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        pending = if (safeArea) progress else progress + 120
                        refreshLabel()
                        // Coalesce rapid events; rebuilding the keyboard on every pixel stalls dragging.
                        if (previewTask == null) {
                            previewTask = Runnable {
                                previewTask = null
                                applyPreview()
                            }.also { previewHandler.postDelayed(it, 60) }
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = flushPreview()
            })
        }
        refreshLabel()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            addView(label)
            addView(slider, LinearLayout.LayoutParams(-1, ctx.dp(64)))
            addView(TextView(ctx).apply {
                setText(if (safeArea) R.string.keyboard_safe_description else R.string.keyboard_height_description)
                setPadding(0, ctx.dp(12), 0, if (safeArea) ctx.dp(24) else ctx.dp(8))
            })
            if (!safeArea) {
                addView(MaterialSwitch(ctx).apply {
                    setText(R.string.font_follows_keyboard_height)
                    isChecked = pendingFontFollow
                    setPadding(0, ctx.dp(8), 0, 0)
                    setOnCheckedChangeListener { _, checked ->
                        pendingFontFollow = checked
                        KeyboardSizePreview.fontFollowHeight = checked
                        ThemeManager.notifyRenderingChanged()
                    }
                })
                addView(TextView(ctx).apply {
                    setText(R.string.font_follows_keyboard_height_summary)
                    setPadding(0, 0, 0, ctx.dp(8))
                })
            }
            addView(LinearLayout(ctx).apply {
                addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.keyboard_size_reset)
                    setOnClickListener {
                        pending = default
                        previewTask?.let(previewHandler::removeCallbacks)
                        previewTask = null
                        preference.setValue(default)
                        clearPreview()
                        if (!safeArea) {
                            KeyboardSizePreview.fontFollowHeight = pendingFontFollow
                            ThemeManager.notifyRenderingChanged()
                        }
                        slider.progress = if (safeArea) 0 else themeHeight - 120
                        refreshLabel()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialButton(ctx).apply {
                    setText(R.string.ok)
                    setOnClickListener {
                        previewTask?.let(previewHandler::removeCallbacks)
                        previewTask = null
                        preference.setValue(pending)
                        fontFollowPreference?.setValue(pendingFontFollow)
                        clearPreview()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(24) })
            })
            addView(EditText(ctx).apply {
                setHint(R.string.test_input)
                isSingleLine = true
                requestFocus()
                post {
                    if (isAttachedToWindow) {
                        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(24) })
        }
        return android.widget.ScrollView(ctx).apply {
            isFillViewport = true
            addView(content)
        }
    }
}

class KeyboardHeightFragment : KeyboardSizeFragment(false)
class KeyboardSafeAreaFragment : KeyboardSizeFragment(true)
