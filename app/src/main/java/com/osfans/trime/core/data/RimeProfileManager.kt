/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import com.osfans.trime.data.prefs.AppPrefs
import java.io.File
import timber.log.Timber

/**
 * 芝版同文：多套 Rime 方案目录（profile）。
 *
 * ```
 * rime/方案/<包>/          直连方案包（v16 起选中包时 user_data_dir 直接指到这里，零复制）
 * rime/方案库/<名>/        手工创建的完整 Rime 目录（「新建方案目录」入口，行为同旧版）
 * ├── *.schema.yaml / *.dict.yaml ...
 * └── build/                 手工目录自己的编译产物
 * ```
 *
 * 词频/用户词典仍然走全局 `rime/学习`，切换方案不会丢学习记录。
 * 空名字表示旧版「运行数据」（把 `rime/方案` 摊平进来的默认目录）。
 *
 * 直连包的编译产物放在 `部署/<包名>/`，包目录保持纯源（Rime 只往里写
 * `user.yaml` / `installation.yaml` 两个小文件），切换方案互不失效。
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

    /**
     * 当前选中且处于直连状态的方案包目录，没有则 null。
     * 摊平/清理逻辑据此对正在使用的包放行（Rime 会往里写运行时小文件）。
     */
    fun activeLinkedPackage(): File? {
        if (currentName().isBlank()) return null
        return linkedPackageDir()
    }

    /** 直连的方案包目录：`方案/<名>/` 存在即视为可用（方案库手工目录优先级更高）。 */
    private fun linkedPackageDir(): File? {
        val name = currentName()
        if (name.isBlank()) return null
        val dir = File(DataManager.schemaRootDir, name)
        return if (dir.isDirectory) dir else null
    }

    /** 手工创建的 `方案库/<名>/` 完整 Rime 目录。 */
    private fun manualProfileDir(): File? {
        val name = currentName()
        if (name.isBlank()) return null
        val dir = File(root, name)
        return if (dir.isDirectory) dir else null
    }

    /**
     * v15 迁移：当年选中包时会把内容复制到 `方案库/<包>/`。现在直连 `方案/<包>/`，
     * 带复制标记的旧目录处理掉——`build/` 挪到 `部署/<包名>/`（保住编译产物），
     * 其余删除。学习数据在全局「学习」，删复制不丢任何东西。
     * 没有复制标记的是用户手工放的目录，尊重不动。
     */
    private fun migrateLegacyCopy() {
        val name = currentName()
        if (name.isBlank()) return
        val copy = File(root, name)
        if (!File(copy, SchemePackageManager.COPY_MARKER).isFile) return
        if (!File(DataManager.schemaRootDir, name).isDirectory) return
        runCatching {
            val oldBuild = File(copy, "build")
            val target = DataManager.stagingDirFor(name)
            if (oldBuild.isDirectory && !target.isDirectory) {
                oldBuild.renameTo(target)
            }
            if (copy.deleteRecursively()) {
                Timber.i("方案目录已改为直连源包，回收旧复制: %s", copy.absolutePath)
            }
        }.onFailure { Timber.w(it, "回收旧方案目录复制失败: %s", name) }
    }

    fun isProfile(): Boolean {
        migrateLegacyCopy()
        val name = currentName()
        return name.isNotBlank() && (manualProfileDir() != null || linkedPackageDir() != null)
    }

    /** Rime 的 user_data_dir：手工方案目录 > 直连方案包 > 默认目录。 */
    fun currentDir(): File = manualProfileDir() ?: linkedPackageDir() ?: legacyDir

    /**
     * Rime 的 staging_dir：手工方案目录用自己的 `build/`；直连方案包用
     * `部署/<包名>/`；默认目录仍用全局「部署」。
     */
    fun stagingDir(): File =
        when {
            manualProfileDir() != null -> File(currentDir(), "build").also { it.mkdirs() }
            linkedPackageDir() != null -> DataManager.stagingDirFor(currentName())
            else -> DataManager.defaultStagingRoot()
        }

    /** `方案库` 下的手工方案目录，按名字排序。 */
    fun listProfiles(): List<File> =
        root
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun switchTo(name: String) {
        prefs.rimeProfile.setValue(name)
    }
}
