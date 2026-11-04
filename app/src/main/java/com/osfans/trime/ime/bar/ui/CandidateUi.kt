// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

class CandidateUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val compatView: View,
) : Ui {
    private val theme: Theme get() = scope.theme

    val unrollButton =
        ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, scope).apply {
            // 芝版同文：必须给 id。下面 compatView 用 `before(unrollButton)` 做约束，
            // 而 splitties 的 before() 取的是 view.id；没 id 时约束等于 id=0 无效，
            // 候选条就变成「宽度不受限、铺到屏幕外」，候选一多就整片盖住这个按钮。
            id = R.id.unroll_button
            visibility = View.INVISIBLE
        }

    override val root =
        ctx.constraintLayout {
            add(
                unrollButton,
                lParams(dp(40)) {
                    centerVertically()
                    endOfParent()
                },
            )
            add(
                compatView,
                // 芝版同文：这里必须是 0dp（matchConstraints）+ 左右都有约束，不能是 wrapContent。
                // 候选条用的是 NOWRAP 的 Flexbox，候选一多（尤其带拆字注释）自身测量宽度会远超屏幕，
                // 而它又是后添加的，会整片盖住右侧的展开按钮——表现就是「多候选时点不开、全挤在一行」。
                // 约束成 0dp 后宽度由两端约束决定，候选在自身范围内裁切/滚动，按钮永远露在最右边。
                lParams(matchConstraints, wrapContent) {
                    centerVertically()
                    startOfParent(dp(theme.style.candidatePadding / 2))
                    before(unrollButton)
                },
            ).also {
                // 芝版同文：唯一可靠的一步——把按钮挪到最上层。
                // 上面那条宽度约束万一没生效（候选条仍然铺到屏幕边缘），
                // 触摸派发是先给后添加的子 View，按钮照样能点；否则点击会被候选条吃掉，
                // 表现就是「点展开没反应，反而选中了一个候选」。
                bringChildToFront(unrollButton)
            }
        }

    /** Restyles the candidate bar after a scheme switch. */
    fun refreshColors() {
        unrollButton.refreshColors()
    }
}
