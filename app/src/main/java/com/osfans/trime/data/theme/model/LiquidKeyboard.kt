/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable(with = LiquidKeyboardSerializer::class)
data class LiquidKeyboard(
    val singleWidth: Int = 0,
    val keyHeight: Int = 0,
    val marginX: Float = 0f,
    val fixedKeyBar: KeyBar = KeyBar(),
    val keyboards: List<Keyboard> = emptyList(),
) {

    @Serializable
    data class KeyBar(
        val keys: List<String> = emptyList(),
        val position: Position = Position.BOTTOM,
    ) {
        enum class Position {
            TOP,
            LEFT,
            BOTTOM,
            RIGHT,
        }
    }

    data class Keyboard(
        val id: String,
        val type: DataType,
        val name: String,
        val keys: List<KeyItem>,
    )

    data class KeyItem(
        val text: String,
        val altText: String,
    ) {
        constructor(text: String) : this(text, text)
    }

    enum class DataType {
        SINGLE,
        SYMBOL,
        TABS,
        HISTORY,

        /**
         * 兄弟分支（同文/仓鼠系）的「变长」液体键盘类型，语义上就是一张普通的键位表，
         * 只是每行键数不固定。这里按 [SYMBOL] 处理即可正常显示。
         *
         * 不能直接删掉这个枚举值：皮肤是第三方作者按别的分支写的，缺了它整份主题 yaml
         * 反序列化会抛 DuplicateKeyException，外观里「主题不可用」然后回落内置主题。
         */
        VAR_LENGTH,
    }

    data class Tag(val label: String = "", val type: DataType)

    @Transient
    private val data = lazy {
        keyboards.map {
            Tag(it.name, it.type) to it.keys.toTypedArray()
        }
    }

    fun getTagList() = data.value.map { it.first }

    fun getDataByIndex(index: Int): List<KeyItem> {
        val item = data.value[index]
        val tag = item.first
        return if (tag.type == DataType.TABS) {
            data.value.mapNotNull {
                if (it.first.type != DataType.TABS) KeyItem(it.first.label) else null
            }
        } else {
            item.second.toList()
        }
    }
}
