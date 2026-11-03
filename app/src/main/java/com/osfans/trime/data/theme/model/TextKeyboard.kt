/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import com.osfans.trime.util.enum
import kotlinx.serialization.Serializable

@Serializable
data class TextKeyboard(
    val name: String = "",
    val author: String = "",
    val width: Float = 0f,
    val height: Float = 0f,
    val keyboardHeight: Int = 0,
    val keyboardHeightLand: Int = 0,
    val autoHeightIndex: Int = -1,
    val horizontalGap: Int = 0,
    val verticalGap: Int = 0,
    val roundCorner: Float = -1f,
    val keyBorder: Int = -1,
    val columns: Int = 30,
    val asciiMode: Int = 1,
    val resetAsciiMode: Boolean = false,
    val labelTransform: LabelTransform = LabelTransform.NONE,
    val lock: Boolean = false,
    val asciiKeyboard: String = "",
    val landscapeKeyboard: String = "",
    val landscapeSplitPercent: Int = 0,
    val keyTextOffsetX: Float = 0f,
    val keyTextOffsetY: Float = 0f,
    val keySymbolOffsetX: Float = 0f,
    val keySymbolOffsetY: Float = 0f,
    val keyHintOffsetX: Float = 0f,
    val keyHintOffsetY: Float = 0f,
    val keyPressOffsetX: Float = 0f,
    val keyPressOffsetY: Float = 0f,
    val importPreset: String = "",
    val keys: List<TextKey> = emptyList(),
) {
    enum class LabelTransform {
        NONE,
        UPPERCASE,
    }

    @Serializable
    data class TextKey(
        val width: Float = 0f,
        val height: Float = 0f,
        /**
         * 芝版：显式矩形（0..1 的相对值）。[xPercent] >= 0 时跳过流式布局，
         * 直接按这四个值摆放——元书皮肤是 HStack/VStack 布局树，有跨行与不等宽的键，
         * 流式模型表达不了。
         */
        val xPercent: Float = -1f,
        val yPercent: Float = -1f,
        val wPercent: Float = -1f,
        val hPercent: Float = -1f,
        val roundCorner: Float = -1f,
        val keyBorder: Int = -1,
        val label: String = "",
        /**
         * 芝版：按键前景图片（相对 backgrounds 的路径）。
         * 元书皮肤的字母本身是精灵图切片，非空时画图而不是画文字。
         */
        val keyForegroundImage: String = "",
        /** 字面标签：真实输入模式（英文/ASCII）下也照用，不回退到动作名。 */
        val fixedLabel: Boolean = false,
        val labelSymbol: String = "",
        /** 芝版：角标是否画在左上角（元书的上滑提示就是这种位置）。 */
        val symbolAtTopLeft: Boolean = false,
        /** 芝版：右上角标（元书的下滑提示，如 q 键的 `!`）。 */
        val labelSymbolRight: String = "",
        val hint: String = "",
        val sendBindings: Boolean = true,
        val keyTextSize: Float = 0f,
        val symbolTextSize: Float = 0f,
        val keyTextOffsetX: Float = 0f,
        val keyTextOffsetY: Float = 0f,
        val keySymbolOffsetX: Float = 0f,
        val keySymbolOffsetY: Float = 0f,
        val keyHintOffsetX: Float = 0f,
        val keyHintOffsetY: Float = 0f,
        val keyPressOffsetX: Float = 0f,
        val keyPressOffsetY: Float = 0f,
        val keyTextColor: String = "",
        val keyBackColor: String = "",
        val keyBorderColor: String = "",
        val keySymbolColor: String = "",
        val hilitedKeyTextColor: String = "",
        val hilitedKeyBackColor: String = "",
        val hilitedKeyBorderColor: String = "",
        val hilitedKeySymbolColor: String = "",
        @Serializable(with = LenientStringListSerializer::class)
        val popup: List<String> = emptyList(),
        // behaviors
        val click: KeyActionToken? = null,
        val doubleClick: KeyActionToken? = null,
        val lazyDoubleClick: KeyActionToken? = null,
        val longClick: KeyActionToken? = null,
        val swipeUp: KeyActionToken? = null,
        val swipeDown: KeyActionToken? = null,
        val swipeLeft: KeyActionToken? = null,
        val swipeRight: KeyActionToken? = null,
        val composing: KeyActionToken? = null,
        val hasMenu: KeyActionToken? = null,
        val paging: KeyActionToken? = null,
        val combo: KeyActionToken? = null,
        val ascii: KeyActionToken? = null,
    ) {
        companion object {
            val DEFAULTS = TextKey()
        }
    }

    companion object {
        val DEFAULTS = TextKeyboard()
    }
}
