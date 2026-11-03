/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 输入统计里的「字词云」。
 *
 * 数据已经是词条（不做中文分词）；字号按词频做 log 映射。布局使用阿基米德
 * 螺线从中心向外试位，碰撞检测用掩码 + 积分图，O(1) 查询。
 */
class WordCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    data class Entry(val text: String, val count: Long)

    private data class Placed(
        val text: String,
        val x: Float,
        val y: Float,
        val size: Float,
        val color: Int,
    )

    private var entries: List<Entry> = emptyList()
    private val placed = mutableListOf<Placed>()

    private val density = resources.displayMetrics.density
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    private val maskPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2.5f * density
        }
    private val emptyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            color =
                MaterialColors.getColor(
                    this@WordCloudView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY,
                )
        }

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var integral: IntArray? = null
    private var gridWidth = 0
    private var gridHeight = 0
    private var cellSize = max(3, (2.5f * density).toInt())

    private val palette by lazy {
        val primary =
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorPrimary,
                Color.BLACK,
            )
        val onSurface =
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurface,
                Color.DKGRAY,
            )
        intArrayOf(
            primary,
            ColorUtils.blendARGB(primary, onSurface, 0.20f),
            ColorUtils.blendARGB(primary, onSurface, 0.40f),
            ColorUtils.blendARGB(primary, onSurface, 0.60f),
        )
    }

    private fun resolveTypeface(): Typeface =
        runCatching { ThemeManager.activeTheme.fonts.wordCloud }
            .getOrElse { Typeface.create("serif", Typeface.NORMAL) }

    init {
        val typeface = resolveTypeface()
        textPaint.typeface = typeface
        maskPaint.typeface = typeface
    }

    /** 从「字体与字号」改完词云字体回来时刷新，不需要重建 Activity。 */
    fun refreshTypeface() {
        val typeface = resolveTypeface()
        textPaint.typeface = typeface
        maskPaint.typeface = typeface
        rebuild()
    }

    fun setEntries(value: List<Entry>) {
        entries = value
        rebuild()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize((300 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (placed.isEmpty()) {
            val y = height / 2f - (emptyPaint.ascent() + emptyPaint.descent()) / 2f
            canvas.drawText(getString(R.string.stats_word_cloud_empty), width / 2f, y, emptyPaint)
            return
        }
        placed.forEach { word ->
            textPaint.textSize = word.size
            textPaint.color = word.color
            canvas.drawText(word.text, word.x, word.y, textPaint)
        }
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        placed.clear()
        if (entries.isEmpty()) {
            mask = null
            maskCanvas = null
            integral = null
            invalidate()
            return
        }

        val sorted = entries.sortedByDescending { it.count }.take(MAX_WORDS)
        val maxCount = sorted.maxOf { it.count }.toDouble().coerceAtLeast(1.0)
        val minCount = sorted.minOf { it.count }.toDouble().coerceAtLeast(1.0)
        val minSize = 12f * density
        val maxSize = 38f * density
        val padding = (4 * density).toInt()

        mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(mask!!)
        gridWidth = (width + cellSize - 1) / cellSize
        gridHeight = (height + cellSize - 1) / cellSize
        integral = IntArray((gridWidth + 1) * (gridHeight + 1))

        sorted.forEachIndexed { index, entry ->
            val ratio =
                if (maxCount > minCount) {
                    (ln(entry.count.toDouble()) - ln(minCount)) / (ln(maxCount) - ln(minCount))
                } else {
                    1.0
                }
            val size = (minSize + (maxSize - minSize) * ratio).toFloat()
            textPaint.textSize = size
            maskPaint.textSize = size
            val bounds = Rect()
            textPaint.getTextBounds(entry.text, 0, entry.text.length, bounds)
            val wordWidth = bounds.width() + padding * 2
            val wordHeight = bounds.height() + padding * 2
            if (wordWidth >= width - padding * 2 || wordHeight >= height - padding * 2) return@forEachIndexed

            val position = findPosition(wordWidth, wordHeight, padding, index)
            if (position == null) return@forEachIndexed

            val (left, top) = position
            val drawX = (left - bounds.left).toFloat()
            val drawY = (top - bounds.top).toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val distance = hypot(left + wordWidth / 2f - centerX, top + wordHeight / 2f - centerY)
            val maxRadius = min(width, height) / 2f
            val rank = if (sorted.size > 1) index.toFloat() / (sorted.size - 1) else 0f
            val alpha =
                (255 - rank * 55 - (distance / maxRadius.coerceAtLeast(1f)) * 65)
                    .toInt()
                    .coerceIn(105, 255)
            val color = ColorUtils.setAlphaComponent(palette[index * palette.size / sorted.size], alpha)

            maskPaint.color = Color.WHITE
            maskCanvas?.drawText(entry.text, drawX, drawY, maskPaint)
            rebuildIntegral()
            placed += Placed(entry.text, drawX, drawY, size, color)
        }
        invalidate()
    }

    private fun findPosition(
        wordWidth: Int,
        wordHeight: Int,
        padding: Int,
        index: Int,
    ): Pair<Int, Int>? {
        val centerX = width / 2
        val centerY = height / 2
        val maxRadius = min(width, height) / 2f
        var step = 0
        while (step < MAX_SPIRAL_STEPS) {
            val angle = step * 0.22f + index * 0.9f
            val radius = step * 0.55f * density
            if (radius > maxRadius) break
            val x = (centerX + cos(angle) * radius - wordWidth / 2f).toInt()
            val y = (centerY + sin(angle) * radius - wordHeight / 2f).toInt()
            if (x >= padding &&
                y >= padding &&
                x + wordWidth <= width - padding &&
                y + wordHeight <= height - padding &&
                insideBall(x, y, wordWidth, wordHeight, padding) &&
                !collides(x, y, wordWidth, wordHeight)
            ) {
                return x to y
            }
            step++
        }
        return null
    }

    /** 整体限制在圆内，让词云看起来是一团而不是铺满矩形。 */
    private fun insideBall(
        x: Int,
        y: Int,
        wordWidth: Int,
        wordHeight: Int,
        padding: Int,
    ): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - padding
        if (radius <= 0f) return false
        val dx = x + wordWidth / 2f - centerX
        val dy = y + wordHeight / 2f - centerY
        return hypot(dx, dy) <= radius
    }

    private fun collides(
        x: Int,
        y: Int,
        wordWidth: Int,
        wordHeight: Int,
    ): Boolean {
        val data = integral ?: return false
        val gx1 = (x / cellSize).coerceIn(0, gridWidth - 1)
        val gy1 = (y / cellSize).coerceIn(0, gridHeight - 1)
        val gx2 = ((x + wordWidth - 1) / cellSize).coerceIn(0, gridWidth - 1)
        val gy2 = ((y + wordHeight - 1) / cellSize).coerceIn(0, gridHeight - 1)
        val stride = gridWidth + 1
        val sum =
            data[(gy2 + 1) * stride + (gx2 + 1)] -
                data[gy1 * stride + (gx2 + 1)] -
                data[(gy2 + 1) * stride + gx1] +
                data[gy1 * stride + gx1]
        return sum > 0
    }

    private fun rebuildIntegral() {
        val bitmap = mask ?: return
        val stride = gridWidth + 1
        val data = IntArray(stride * (gridHeight + 1))
        for (gy in 0 until gridHeight) {
            val py = min(gy * cellSize + cellSize / 2, height - 1)
            for (gx in 0 until gridWidth) {
                val px = min(gx * cellSize + cellSize / 2, width - 1)
                val alpha = Color.alpha(bitmap.getPixel(px, py))
                data[(gy + 1) * stride + (gx + 1)] =
                    alpha +
                    data[gy * stride + (gx + 1)] +
                    data[(gy + 1) * stride + gx] -
                    data[gy * stride + gx]
            }
        }
        integral = data
    }

    private fun getString(id: Int): String = context.getString(id)

    private companion object {
        const val MAX_WORDS = 100
        const val MAX_SPIRAL_STEPS = 4000
    }
}
