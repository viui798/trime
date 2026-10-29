/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import java.util.WeakHashMap

/** Draw inset groups using the visible adapter order, including after search or dependency changes. */
@SuppressLint("RestrictedApi", "NewApi")
internal class InsetPreferenceDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val surface = ContextCompat.getColor(context, R.color.settings_surface_container_lowest)
    private val separator = ContextCompat.getColor(context, R.color.settings_separator)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val shapes = WeakHashMap<View, Int>()

    private fun isRow(preference: Preference?): Boolean =
        preference?.layoutResource == R.layout.preference_settings_row ||
            preference?.layoutResource == R.layout.preference_settings_link_row

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val preference = adapter.getItem(position) ?: continue
            if (!isRow(preference)) continue

            fun sharesGroup(otherPosition: Int): Boolean {
                if (otherPosition !in 0 until adapter.itemCount) return false
                val other = adapter.getItem(otherPosition)
                return isRow(other) && other?.parent === preference.parent
            }

            val first = !sharesGroup(position - 1)
            val last = !sharesGroup(position + 1)
            val topRadius = if (first) 18 * density else 0f
            val bottomRadius = if (last) 18 * density else 0f
            val radii = floatArrayOf(
                topRadius, topRadius, topRadius, topRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius,
            )
            bounds.set(
                child.left + 20 * density + child.translationX,
                child.top + child.translationY,
                child.right - 20 * density + child.translationX,
                child.bottom + child.translationY,
            )
            path.reset()
            path.addRoundRect(bounds, radii, Path.Direction.CW)
            paint.color = surface
            paint.alpha = (255 * child.alpha).toInt()
            canvas.drawPath(path, paint)

            // The mask follows the group corners without replacing Preference click handling.
            val shape = (if (first) 1 else 0) or (if (last) 2 else 0)
            val card = child.findViewById<View>(R.id.settings_card) ?: continue
            card.alpha = if (preference.isEnabled) 1f else 0.45f
            val frame = child as? FrameLayout ?: continue
            if (shapes[frame] != shape) {
                shapes[frame] = shape
                frame.setForeground(
                    RippleDrawable(
                    ColorStateList.valueOf(separator and 0x00FFFFFF or 0x33000000),
                    null,
                    InsetDrawable(
                        GradientDrawable().apply {
                            setColor(android.graphics.Color.WHITE)
                            cornerRadii = radii
                        },
                        (20 * density).toInt(), 0, (20 * density).toInt(), 0,
                    ),
                    ),
                )
            }
            if (!last) {
                paint.color = separator
                paint.alpha = (180 * child.alpha).toInt()
                paint.strokeWidth = density * 0.5f
                val inset = if (preference.icon == null) 16 else 58
                val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
                canvas.drawLine(
                    bounds.left + (if (rtl) 16 else inset) * density,
                    bounds.bottom - paint.strokeWidth / 2,
                    bounds.right - (if (rtl) inset else 16) * density,
                    bounds.bottom - paint.strokeWidth / 2,
                    paint,
                )
            }
        }
    }
}
