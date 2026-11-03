/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.core.view.children
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.KeyboardSizePreview
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    // 芝版同文：设置里的「字体与字号」可以按用途覆盖皮肤给定的字号；未覆盖时保持原样。
    private val baseKeyTextSize =
        FontOverrides.sizeOrNull(FontOverrides.Scope.KEY)
            ?: theme.style.keyTextSize

    /** 开启「字体跟随变形」时，以主题/自定义布局的原高度为 1.0 缩放字号。 */
    private val fontScale: Float =
        if (!KeyboardSizePreview.fontFollowsHeight()) {
            1f
        } else {
            val base = keyboard.baseKeyboardHeight
            if (base <= 0) {
                1f
            } else {
                (keyboard.keyboardHeight.toFloat() / base).coerceIn(0.5f, 3f)
            }
        }

    private fun scaledFont(size: Float): Float = (size * fontScale).coerceAtLeast(1f)

    internal val keyTextSize = scaledFont(baseKeyTextSize)
    internal val keyLongTextSize =
        scaledFont(
            FontOverrides.sizeOrNull(FontOverrides.Scope.KEY)
                ?: theme.style.keyLongTextSize.takeIf { it > 0 }
                ?: baseKeyTextSize,
        )
    internal val symbolTextSize =
        scaledFont(
            FontOverrides.sizeOrNull(FontOverrides.Scope.SYMBOL)
                ?: theme.style.symbolTextSize.takeIf { it > 0 }
                ?: baseKeyTextSize,
        )
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress
    internal val hookShiftArrow: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftArrow
    internal val hideKeySymbol: Boolean by AppPrefs.defaultInstance().keyboard.hideKeySymbol
    internal val hideKeyHint: Boolean by AppPrefs.defaultInstance().keyboard.hideKeyHint

    init {
        setWillNotDraw(false)
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, theme, key, keyboard, this, keyboardActionListener).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            keyboard.verticalGap / 2,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    /** 只重绘回车键：它的文字随输入框变化，没必要连带重画整把键盘。 */
    fun invalidateEnterKeys() {
        children.forEach { child ->
            if (child is KeyView && child.isEnterLabelKey) child.invalidate()
        }
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        popup.dismissAll()
    }
}
