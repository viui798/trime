/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.core.data.DataManager
import java.io.File

/**
 * 芝版同文：皮肤资源直读。
 *
 * 主题 yaml 仍然会摊平到「运行数据」（文件很小，而且 [ThemeLoader] 的
 * librime fallback 需要它），但 `backgrounds/` 和 `fonts/` 不再复制——
 * 按当前主题的 configId 找到 `皮肤/<包>/`，直接从包里读。
 *
 * configId 的约定和 [ThemeFilesManager] 一致：`咖啡.trime.yaml` → `咖啡.trime`。
 * 找不到包目录时回落到「运行数据」，再回落「shared」，所以内置主题和
 * 方案包里自带的 theme 不受影响。
 */
object ThemeAssets {
    @Volatile
    private var packageDir: File? = null

    /** 切换主题时调用；[configId] 没有对应包目录时传 null 即可。 */
    fun select(configId: String?) {
        packageDir = configId?.let(::findPackageDir)
    }

    fun clear() {
        packageDir = null
    }

    fun resolve(relative: String): File? {
        val rel = relative.trimStart('/')
        packageDir?.let { dir ->
            File(dir, rel).takeIf { it.isFile }?.let { return it }
        }
        File(DataManager.userDataDir, rel).takeIf { it.isFile }?.let { return it }
        return File(DataManager.sharedDataDir, rel).takeIf { it.isFile }
    }

    /** 字体目录名各家不一，`fonts` 和 `Fonts` 都认。 */
    fun resolveFont(name: String): File? {
        resolve("fonts/$name")?.let { return it }
        return resolve("Fonts/$name")
    }

    private fun findPackageDir(configId: String): File? {
        val fileName = "$configId.yaml"
        return DataManager.themeRootDir
            .listFiles().orEmpty().asSequence()
            .firstOrNull { it.isFile && it.name == fileName }
            ?.parentFile
    }
}
