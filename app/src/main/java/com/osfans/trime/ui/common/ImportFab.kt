/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.res.ColorStateList
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.osfans.trime.R
import splitties.resources.styledColor

/**
 * 芝版同文：方案页与外观页右下角的「导入」悬浮按钮。
 *
 * 两处**必须长得一样**，所以样式只在这里定义一份：
 * - 背景用 FAB 默认的 `colorPrimaryContainer`
 * - 图标色用对应的 `colorOnPrimaryContainer`（不要写死白色：深色模式下会刺眼，
 *   也不要用 `colorForegroundInverse`：它随主题反转，两页会不一致）
 * - 离屏幕边缘 24dp，比 Material 默认的 16dp 宽一点，别贴着角落
 */
object ImportFab {
    const val MARGIN_DP = 24

    fun decorate(fab: FloatingActionButton) {
        fab.setImageResource(R.drawable.ic_baseline_add_24)
        fab.imageTintList =
            ColorStateList.valueOf(
                fab.context.styledColor(com.google.android.material.R.attr.colorOnPrimaryContainer),
            )
    }
}
