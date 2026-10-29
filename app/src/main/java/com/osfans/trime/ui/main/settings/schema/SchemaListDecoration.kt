/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R

/** Shared rounded container and inset separators for the custom schema RecyclerView. */
internal class SchemaListDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val surface =
        ContextCompat.getColor(context, R.color.settings_surface_container_lowest)
    private val separator = ContextCompat.getColor(context, R.color.settings_separator)

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val itemCount = parent.adapter?.itemCount ?: return
        if (itemCount == 0) return

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val first = position == 0
            val last = position == itemCount - 1
            val topRadius = if (first) 18 * density else 0f
            val bottomRadius = if (last) 18 * density else 0f
            bounds.set(
                child.left + 20 * density + child.translationX,
                child.top + child.translationY,
                child.right - 20 * density + child.translationX,
                child.bottom + child.translationY,
            )
            path.reset()
            path.addRoundRect(
                bounds,
                floatArrayOf(
                    topRadius, topRadius, topRadius, topRadius,
                    bottomRadius, bottomRadius, bottomRadius, bottomRadius,
                ),
                Path.Direction.CW,
            )
            paint.color = surface
            paint.alpha = (255 * child.alpha).toInt()
            canvas.drawPath(path, paint)

            if (!last) {
                paint.color = separator
                paint.alpha = (180 * child.alpha).toInt()
                paint.strokeWidth = density * 0.5f
                canvas.drawLine(
                    bounds.left + 58 * density,
                    bounds.bottom - paint.strokeWidth / 2,
                    bounds.right - 16 * density,
                    bounds.bottom - paint.strokeWidth / 2,
                    paint,
                )
            }
        }
    }
}
