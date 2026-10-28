/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import com.osfans.trime.data.prefs.AppPrefs
import java.io.File

/**
 * 芝版同文：多套 Rime 方案目录（profile）。
 *
 * ```
 * rime/方案库/<方案名>/     一个完整的 Rime user_data_dir
 * ├── *.schema.yaml / *.dict.yaml / build/ ...
 * └── build/                 该方案的编译产物（staging_dir）
 * ```
 *
 * 词频/用户词典仍然走全局 `rime/学习`，切换方案不会丢学习记录。
 * 空名字表示旧版「运行数据」（把 `rime/方案` 摊平进来的默认目录）。
 */
object RimeProfileManager {
    const val PROFILE_ROOT_NAME = "方案库"

    private val prefs get() = AppPrefs.defaultInstance().profile

    /** `rime/方案库`，不存在时创建。 */
    val root: File
        get() = File(DataManager.rimeRootDir, PROFILE_ROOT_NAME).also { it.mkdirs() }

    /** 旧版默认目录，`rime/运行数据`。 */
    val legacyDir: File get() = DataManager.userDataDir

    /** 当前方案目录名；空字符串表示默认目录。 */
    fun currentName(): String = prefs.rimeProfile.getValue()

    fun isProfile(): Boolean {
        val name = currentName()
        return name.isNotBlank() && File(root, name).isDirectory
    }

    /** Rime 的 user_data_dir：选中的方案目录，否则默认目录。 */
    fun currentDir(): File = if (isProfile()) File(root, currentName()) else legacyDir

    /** Rime 的 staging_dir：方案自己的 `build/`，默认目录仍用全局「部署」。 */
    fun stagingDir(): File =
        if (isProfile()) {
            File(currentDir(), "build").also { it.mkdirs() }
        } else {
            DataManager.deployDir
        }

    /** `方案库` 下的方案目录，按名字排序。 */
    fun listProfiles(): List<File> =
        root
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun switchTo(name: String) {
        prefs.rimeProfile.setValue(name)
    }
}
