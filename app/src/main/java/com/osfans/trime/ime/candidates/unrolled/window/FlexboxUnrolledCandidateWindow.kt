/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.view.Gravity
import android.view.ViewGroup
import androidx.transition.Slide
import androidx.transition.Transition
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxHorizontalDecoration
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import splitties.dimensions.dp
import splitties.views.dsl.core.wrapContent

class FlexboxUnrolledCandidateWindow(di: DI) : BaseUnrolledCandidateWindow(di) {
    override fun exitAnimation(nextWindow: BoardWindow): Transition = Slide().apply {
        slideEdge = Gravity.TOP
    }

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(scope) {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): CandidateViewHolder = super.onCreateViewHolder(parent, viewType).apply {
                itemView.apply {
                    minimumWidth = dp(40)
                    val itemHeight = dp(theme.style.run { candidateViewHeight + commentHeight })
                    layoutParams =
                        FlexboxLayoutManager
                            .LayoutParams(wrapContent, itemHeight)
                            .apply {
                                flexGrow = 1f
                                // 芝版同文：必须禁用收缩。flexbox 默认 flexShrink = 1，
                                // 一行塞不下时会把候选压窄（文字本身不缩放，于是溢出、互相压住），
                                // 表现就是「展开后第一行挤成一团、后面的行才正常」。
                                // 置 0 后装不下就换行，每行按 flexGrow 均分。
                                flexShrink = 0f
                            }
                }
            }

            override fun onBindViewHolder(
                holder: CandidateViewHolder,
                position: Int,
            ) {
                super.onBindViewHolder(holder, position)
                bindCandidateUiViewHolder(holder)
            }
        }
    }

    override val layoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            justifyContent = JustifyContent.SPACE_AROUND
            alignItems = AlignItems.FLEX_START
        }
    }

    override fun onCreateCandidateLayout(): UnrolledCandidateLayout = UnrolledCandidateLayout(context, scope).apply {
        recyclerView.apply {
            adapter = this@FlexboxUnrolledCandidateWindow.adapter
            layoutManager = this@FlexboxUnrolledCandidateWindow.layoutManager
            addItemDecoration(FlexboxHorizontalDecoration(separatorDrawable))
        }
    }
}
