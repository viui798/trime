/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 元书皮肤的布局树求值。
 *
 * cskin 的 `keyboardLayout` 是一棵容器树：
 *
 * ```
 * keyboardLayout: [              // 顶层：元素是 HStack 时纵向堆叠（行），是 VStack 时横向排列（列）
 *   { HStack: { subviews: [ {Cell: "qButton"}, ... ] } },
 *   { VStack: { style: "VStackStyle1", subviews: [ {Cell: "collection"}, {Cell: "symbolButton"} ] } },
 * ]
 * ```
 *
 * 每个单元格的尺寸来自三处，优先级从高到低：
 * 1. 按钮自己的 `size.width/height`
 * 2. 容器 style 的 `size`
 * 3. 在兄弟节点之间均分
 *
 * 尺寸可以是百分比对象 `{percentage: 0.1}`、分数串 `"29/183"` / `"3/4"`，或数字。
 * 这里把整棵树折平成「相对矩形」（0..1），交给同文的 [com.osfans.trime.ime.keyboard.Keyboard]
 * 按显式坐标摆放——这样元书那种「一列里 4 个键挤在 3 行高度内」的写法也能表达。
 */
object HamsterLayout {
    /** 一个单元格在键盘中的相对位置（0..1）。 */
    data class Cell(
        val name: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    fun layout(root: JsonObject): List<Cell> {
        val top = root["keyboardLayout"] as? JsonArray ?: return emptyList()
        if (top.isEmpty()) return emptyList()

        val out = mutableListOf<Cell>()
        val vertical = top.firstOrNull()?.let { it is JsonObject && it.containsKey("HStack") } == true
        if (vertical) {
            // 每个元素是一行，行高均分。
            val h = 1f / top.size
            top.forEachIndexed { index, element ->
                layoutNode(root, element, 0f, index * h, 1f, h, out)
            }
        } else {
            // 每个元素是一列，列宽按 style / 按钮尺寸。
            val widths = resolveShares(root, top, horizontal = true)
            var x = 0f
            top.forEachIndexed { index, element ->
                val w = widths[index]
                layoutNode(root, element, x, 0f, w, 1f, out)
                x += w
            }
        }
        return out
    }

    private fun layoutNode(
        root: JsonObject,
        element: JsonElement,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        out: MutableList<Cell>,
    ) {
        val obj = element as? JsonObject ?: return

        obj["HStack"]?.let { stackElement ->
            val stack = stackElement as? JsonObject ?: return
            val subs = stack["subviews"] as? JsonArray ?: return
            val shares = resolveShares(root, subs, horizontal = true)
            var cx = x
            subs.forEachIndexed { index, sub ->
                val w = shares[index] * width
                layoutNode(root, sub, cx, y, w, height, out)
                cx += w
            }
            return
        }

        obj["VStack"]?.let { stackElement ->
            val stack = stackElement as? JsonObject ?: return
            val subs = stack["subviews"] as? JsonArray ?: return
            val shares = resolveShares(root, subs, horizontal = false)
            var cy = y
            subs.forEachIndexed { index, sub ->
                val h = shares[index] * height
                layoutNode(root, sub, x, cy, width, h, out)
                cy += h
            }
            return
        }

        obj["Cell"]?.let { cellElement ->
            val name = (cellElement as? JsonPrimitive)?.contentOrNull() ?: return
            out += Cell(name, x, y, width, height)
        }
    }

    /**
     * 算一组兄弟节点各占多少（相对父容器，总和为 1）。
     *
     * 缺省尺寸的节点平分「1 减去已指定比例」剩下的部分；全都指定了但总和不等于 1 时归一化。
     */
    private fun resolveShares(
        root: JsonObject,
        items: JsonArray,
        horizontal: Boolean,
    ): FloatArray {
        val declared = items.map { item ->
            val name = (item as? JsonObject)?.get("Cell")
                ?.let { (it as? JsonPrimitive)?.contentOrNull() }
            if (name == null) {
                null
            } else {
                val def = root[name] as? JsonObject
                parseDimension(def?.get("size") as? JsonObject, horizontal)
            }
        }

        val assigned = declared.filterNotNull().sum()
        val missing = declared.count { it == null }
        val share = when {
            missing > 0 -> ((1f - assigned).coerceAtLeast(0f)) / missing
            assigned > 0f -> 1f / assigned // 全指定但总和 ≠ 1 → 归一化
            else -> 1f / items.size
        }

        return FloatArray(items.size) { index ->
            val value = declared[index] ?: share
            if (missing > 0) value else value * share
        }
    }

    /** 读 `size.width` / `size.height`：支持 `{percentage}`、`"a/b"` 与数字。 */
    private fun parseDimension(
        size: JsonObject?,
        horizontal: Boolean,
    ): Float? {
        val raw = size?.get(if (horizontal) "width" else "height") ?: return null
        return parseFraction(raw)
    }

    private fun parseFraction(element: JsonElement): Float? = when (element) {
        is JsonPrimitive -> parsePrimitive(element)
        is JsonObject -> (element["percentage"] as? JsonPrimitive)?.contentOrNull()?.toFloatOrNull()
        else -> null
    }

    private fun parsePrimitive(primitive: JsonPrimitive): Float? {
        val text = primitive.contentOrNull()?.trim() ?: return null
        if ("/" in text) {
            val parts = text.split("/")
            if (parts.size != 2) return null
            val numerator = parts[0].trim().toFloatOrNull() ?: return null
            val denominator = parts[1].trim().toFloatOrNull() ?: return null
            if (denominator == 0f) return null
            return numerator / denominator
        }
        return text.toFloatOrNull()
    }
}

/** JsonPrimitive 取原始文本；null 值返回 null（`content` 会抛异常）。 */
private fun JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
