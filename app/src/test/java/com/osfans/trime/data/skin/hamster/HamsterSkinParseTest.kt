/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import io.kotest.core.spec.style.FunSpec
import timber.log.Timber
import java.io.File

class HamsterSkinParseTest : FunSpec({
    test("dump default skin") {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    println("[$tag] $message")
                }
            },
        )
        val dir = File("/tmp/ham/hamster")
        val parsed = if (dir.isDirectory) HamsterSkinParser.parse(dir, dark = false) else null
        println("目录存在=${dir.isDirectory} parsed=${parsed != null}")
        if (parsed != null) {
            println("键盘=${parsed.name} 套数=${parsed.keyboards.size} ids=${parsed.keyboards.keys}")
            val main = parsed.keyboards["default"]
            println("主键盘行数=${main?.rows?.size}")
            main?.rows?.forEachIndexed { i, row ->
                println("行${i + 1}: " + row.joinToString(" | ") { "${it.label.ifBlank { "·" }}(${it.widthPercent})/${it.click}" })
            }
            val theme = HamsterThemeBuilder.build("hamster", parsed, dark = false)
            val keyboard = theme.presetKeyboards["default"]
            println("主题键数=${keyboard?.keys?.size} 行数=${keyboard?.height} 键盘宽=${keyboard?.width}")
            println("首键=${keyboard?.keys?.first()?.label} 底色=${keyboard?.keys?.first()?.keyBackColor} 字色=${keyboard?.keys?.first()?.keyTextColor}")
            println("空格键=${keyboard?.keys?.firstOrNull { it.label == "空格" }?.width}")
        }
    }
})
