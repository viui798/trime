/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import android.content.res.AssetManager
import android.os.Build
import com.osfans.trime.TrimeApplication
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    private fun serializeDataChecksums(checksums: DataChecksums): String = json.encodeToString(checksums)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    private val externalFilesDir get() = TrimeApplication.getInstance().externalFilesDir

    val sharedDataDir = File(externalFilesDir, "shared").also { it.mkdirs() }

    /**
     * 芝版同文：`rime/` 是容器，按用途分成五个平级目录。
     *
     * ```
     * rime/
     * ├── 方案/       用户放输入方案，一包一个文件夹
     * ├── 皮肤/       用户放键盘皮肤，一包一个文件夹
     * ├── 部署/       Rime 的编译产物（staging_dir）
     * ├── 学习/       用户词典 userdb + 同步快照
     * └── 运行数据/   Rime 真正的 user_data_dir（摊平后的方案源 + 状态），勿手改
     * ```
     *
     * 为什么要摊平：librime 找方案和词库都用非递归的 `fs::directory_iterator`，
     * `SchemaUpdate` 更是直接拼 `<schema_id>.schema.yaml`，子目录里的文件它根本看不见。
     * 所以 方案/皮肤 只是「源」，Rime 读的是 [userDataDir]。见 [RimeDataLayout]。
     */
    val rimeRootDir = File(externalFilesDir, "rime").also { it.mkdirs() }

    /** 用户放方案的目录，一包一个文件夹。 */
    val schemaRootDir = File(rimeRootDir, "方案").also { it.mkdirs() }

    /** 用户放皮肤的目录，一包一个文件夹。 */
    val themeRootDir = File(rimeRootDir, "皮肤").also { it.mkdirs() }

    /** Rime 的编译产物（相当于 user_data_dir/build，但独立出来可单独清理）。 */
    val deployDir = File(rimeRootDir, "部署").also { it.mkdirs() }

    /** 学习数据：用户词典（*.userdb）与同步快照。 */
    val learnDir = File(rimeRootDir, "学习").also { it.mkdirs() }

    /** Rime 的 user_data_dir。摊平源与运行状态都在这里，属于引擎内部目录。 */
    val userDataDir = File(rimeRootDir, "运行数据").also { it.mkdirs() }

    val prebuiltDataDir = File(sharedDataDir, "build")

    /** 与 librime 的 staging_dir 对齐；不要再用 `运行数据/build`。 */
    val stagingDir = deployDir

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        val destChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            destChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> appContext.assets.copyFile(it.path)

                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> removePath(it.path).getOrThrow()
            }
        }

        destChecksumsFile.bufferedWriter().use {
            it.write(serializeDataChecksums(newChecksums))
        }

        Timber.d("Synced!")
    }

    private fun AssetManager.copyFile(filename: String) {
        open(filename).use { i ->
            File(externalFilesDir, filename)
                .also { it.parentFile?.mkdirs() }
                .outputStream()
                .use { o -> i.copyTo(o) }
        }
    }

    private fun removePath(path: String) = FileUtils.delete(externalFilesDir.resolve(path))
}
