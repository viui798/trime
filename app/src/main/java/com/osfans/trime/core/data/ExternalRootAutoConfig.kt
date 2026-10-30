/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.core.data

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import com.osfans.trime.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File

/**
 * 芝版同文「免选即外部」：首次启动把引擎数据根自动指到外部存储的固定目录，
 * 用户不用挑目录，数据落在一个固定的、文件管理器可见的位置。
 *
 * 目标目录：`/storage/emulated/0/rime-cheese`（外部存储第一卷顶层）。
 * 写这个位置需要「所有文件访问」权限（Android 11+ 的 MANAGE_EXTERNAL_STORAGE），
 * 该权限无法静默授予，只能跳系统设置页让用户拨一次开关；授予后其余全自动——
 * 建目录、迁移既有 方案/皮肤/学习、切换引擎根、缓存失效，Rime 随后重启生效。
 *
 * 与「外部同步（EXTERNAL_SYNC）」通道互不干扰：引擎根外部化后存储模式保持
 * 应用专属（APP_STORAGE），不走 SAF，避免两套目录机制互相覆盖。
 * 用户在设置里手动改过引擎根（externalEngineRoot 非空）时绝不覆盖。
 */
object ExternalRootAutoConfig {
    /** 外部存储上的固定目录名。 */
    const val AUTO_ROOT_NAME = "rime-cheese"

    /** 需要迁移的目录，与手动切换引擎目录的清单保持一致。 */
    private val MIGRATED_DIRS = listOf("方案", "皮肤", "学习")

    private val prefs get() = AppPrefs.defaultInstance().profile

    /** 是否还需要自动配置（引擎根为空、没成功配置过、用户也没拒绝过）。 */
    fun pending(): Boolean =
        prefs.externalEngineRoot.getValue().isBlank() &&
            !prefs.externalRootAutoDone.getValue() &&
            !prefs.externalRootAutoDismissed.getValue()

    /** 「所有文件访问」是否已就绪（Android 11+ 需授权；低版本共享存储直接可写）。 */
    fun permissionReady(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun targetDir(): File = File(Environment.getExternalStorageDirectory(), AUTO_ROOT_NAME)

    fun markDone() {
        prefs.externalRootAutoDone.setValue(true)
    }

    fun markDismissed() {
        prefs.externalRootAutoDismissed.setValue(true)
    }

    /** 跳系统「所有文件访问」设置页；回来后由调用方在 onCreate 里重新检查。 */
    fun openPermissionSettings(activity: Activity) {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}"),
            )
        runCatching { activity.startActivity(intent) }
            .onFailure {
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
    }

    /**
     * 把引擎根指向 [targetDir] 并迁移既有数据；必须在权限就绪后调用（IO 线程）。
     *
     * @return 是否发生了数据迁移（用于提示文案）。抛异常 = 迁移失败，调用方不应
     * 标记完成，避免半拷贝状态下切换根目录。
     */
    fun apply(): Boolean {
        val target = targetDir()
        if (!target.exists() && !target.mkdirs()) {
            Timber.w("外部数据目录创建失败: %s", target.absolutePath)
            error("cannot create ${target.absolutePath}")
        }
        val oldRoot = DataManager.rimeRootDir
        var migrated = false
        val different =
            runCatching { oldRoot.canonicalPath != target.canonicalPath }.getOrDefault(true)
        if (oldRoot.isDirectory && different) {
            migrated = true
        }
        StorageLocation.switchTo(DataStorageMode.DIRECT_EXTERNAL, target.absolutePath)
        markDone()
        return migrated
    }

    private fun copyDirectory(
        src: File,
        dst: File,
    ) {
        SafeDirectoryCopy.copy(src, dst)
    }
}
