/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import android.content.res.Configuration
import com.osfans.trime.core.data.RimeDataLayout
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeItem
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File

/**
 * 元书皮肤管理：皮肤包由「导入」解到 Rime 根目录下的 `<包名>/`，
 * 只要该目录里有 `config.yaml` 就当一套皮肤。
 *
 * 皮肤的 configId 统一带 `hamster:` 前缀，用来和旧的 `.trime.yaml` 主题区分。
 */
object HamsterSkinManager {
    const val PREFIX = "hamster:"

    data class Skin(
        val id: String,
        val name: String,
        val dir: File,
    )

    fun list(): List<Skin> {
        val root = RimeDataLayout.themeDir
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "config.yaml").isFile }
            .sortedBy { it.name.lowercase() }
            .map { Skin(PREFIX + it.name, it.name, it) }
    }

    fun listItems(): List<ThemeItem> = list().map { ThemeItem(it.id, it.name) }

    fun find(configId: String): Skin? = list().firstOrNull { it.id == configId }

    /** 按当前夜间模式挑 `light/` 或 `dark/`，读不出来再试另一套。 */
    fun buildTheme(configId: String): Theme? {
        val skin = find(configId) ?: run {
            Timber.w("hamster: 找不到皮肤 %s", configId)
            return null
        }
        val night = isNightMode()
        val parsed = HamsterSkinParser.parse(skin.dir, dark = night)
            ?: HamsterSkinParser.parse(skin.dir, dark = !night)
            ?: run {
                Timber.w("hamster: %s 里没有可用的键盘定义", skin.name)
                return null
            }
        return runCatching { HamsterThemeBuilder.build(skin.name, parsed, night) }
            .onFailure { Timber.w(it, "hamster: 构建主题失败 %s", skin.name) }
            .getOrNull()
    }

    private fun isNightMode(): Boolean =
        (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
