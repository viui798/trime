/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.content.Context
import android.view.View
import android.view.ViewGroup
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.checkBox
import splitties.views.dsl.core.lParams as coreLParams
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

class SchemaListEntryUi(
    override val ctx: Context,
) : Ui {
    val checkBox = checkBox()

    val nameText =
        textView {
            setPaddingDp(0, 12, 0, 0)
            textSize = 17f
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
        }

    val subtitleText =
        textView {
            setPaddingDp(0, 0, 0, 12)
            textSize = 13f
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            alpha = 0.8f
        }

    override val root: View =
        constraintLayout {
            layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
            minHeight = dp(64)

            add(
                checkBox,
                lParams {
                    width = dp(30)
                    height = matchConstraints
                    centerVertically()
                    startOfParent(dp(36))
                },
            )

            val textContainer =
                verticalLayout {
                        add(nameText, coreLParams(matchParent, wrapContent))
                    add(subtitleText, coreLParams(matchParent, wrapContent))
                }
            add(
                textContainer,
                lParams {
                    width = matchConstraints
                    height = wrapContent
                    centerVertically()
                    after(checkBox, dp(8))
                },
            )
        }
}
