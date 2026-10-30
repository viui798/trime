/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import android.content.res.AssetManager
import android.os.Build
import android.os.Environment
import com.osfans.trime.TrimeApplication
import com.osfans.trime.data.prefs.AppPrefs
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
     *
     * 根目录可配置：默认在应用私有目录；用户在设置里选了外部目录、且系统已授予
     * 「所有文件访问」时改用它（librime 只认真实路径，SAF 的 content:// 它读不了）。
     */
    val rimeRootDir: File
        get() = cachedRimeRoot ?: computeRimeRoot().also { cachedRimeRoot = it }

    @Volatile
    private var cachedRimeRoot: File? = null

    private fun computeRimeRoot(): File {
        val profile = AppPrefs.defaultInstance().profile
        val migrationPrefs = profile.dataStorageMode.sharedPreferences
        if (!migrationPrefs.getBoolean("traditional_storage_modes_v1", false)) {
            migrationPrefs.edit().apply {
                if (profile.externalEngineRoot.getValue().isNotBlank()) {
                    putString(profile.dataStorageMode.key, DataStorageMode.DIRECT_EXTERNAL.name)
                }
                putBoolean("traditional_storage_modes_v1", true)
            }.commit()
        }
        val custom = externalEngineRootPref.trim()
        if (custom.isNotEmpty() && AppPrefs.defaultInstance().profile.dataStorageMode.getValue() == DataStorageMode.DIRECT_EXTERNAL) {
            val dir = File(custom)
            if (canUseExternalRoot(dir)) return dir.also { it.mkdirs() }
            Timber.w("外部数据目录不可用，回落到应用私有目录: %s", custom)
        }
        return File(externalFilesDir, "rime")
    }

    /** 切换外部目录或拿到权限后调用，让 [rimeRootDir] 重新计算。 */
    fun invalidateRimeRootCache() {
        cachedRimeRoot = null
    }

    private val externalEngineRootPref: String
        get() = AppPrefs.defaultInstance().profile.externalEngineRoot.getValue()

    /** 当前是否真的在用外部目录（false 表示回落到了应用私有目录）。 */
    fun isUsingExternalRoot(): Boolean {
        val custom = externalEngineRootPref.trim()
        return AppPrefs.defaultInstance().profile.dataStorageMode.getValue() == DataStorageMode.DIRECT_EXTERNAL && custom.isNotEmpty() && canUseExternalRoot(File(custom))
    }

    /**
     * 能否把引擎目录放在 [dir]。
     *
     * Android 11+ 必须拿到 `MANAGE_EXTERNAL_STORAGE`（系统设置里的「所有文件访问」），
     * 否则即使路径存在也读不到。低版本按能否写入判断。
     */
    fun canUseExternalRoot(dir: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return false
        }
        return runCatching {
            dir.mkdirs()
            dir.canWrite()
        }.getOrDefault(false)
    }

    /** 用户放方案的目录，一包一个文件夹。 */
    val schemaRootDir: File get() = rimeRootDir

    /** 用户放皮肤的目录，一包一个文件夹。 */
    val themeRootDir: File get() = rimeRootDir

    /**
     * 「部署」的**根**。直连方案包 / 方案库模式下真正的 staging 是它下面的
     * `部署/<方案目录名>/`，所以不要拿它去读已部署文件——用 [stagingDir]。
     *
     * 故意设为 private：路径只有一个出口，外部拿不到这个目录就没法写错。
     * 需要 `部署/<名>/` 时用 [stagingDirFor]。
     */
    private val deployDir: File get() = File(rimeRootDir, "build")

    /** `部署/<方案目录名>/`：直连方案包 / 方案库模式的 staging，同时用于该模式的 [stagingDir]。 */
    fun stagingDirFor(name: String): File = File(deployDir, name).also { it.mkdirs() }

    /**
     * 默认模式（没有方案目录）的 staging，就是「部署」根。
     *
     * 只给 [RimeProfileManager] 推算 staging 用——业务代码读已部署文件请用 [stagingDir]，
     * 它会自动跟随当前模式。返回「部署」根而不是 [stagingDir]，是为了避免
     * 推算时读到上一轮残留的 override。
     */
    internal fun defaultStagingRoot(): File = deployDir

    /** 学习数据：用户词典（*.userdb）与同步快照。 */
    val learnDir: File get() = rimeRootDir

    /** Rime 的 user_data_dir。摊平源与运行状态都在这里，属于引擎内部目录。 */
    val userDataDir: File get() = rimeRootDir

    val prebuiltDataDir = File(sharedDataDir, "build")

    /**
     * 与 librime 的 staging_dir 对齐；不要再用 `运行数据/build`。
     *
     * **读「已部署文件」（主题、配色等）一律走这里**，不要直接拼 [deployDir]：
     * 直连方案包 / 方案库模式下 Rime 的 staging 是 `部署/<方案目录名>/`，
     * 由 [com.osfans.trime.core.Rime] 在启动时通过 [setStagingDir] 指过去。
     * 写死 [deployDir] 的代码在该模式下会找不到文件、静默回落默认值。
     */
    val stagingDir: File get() = stagingDirOverride ?: deployDir

    @Volatile
    private var stagingDirOverride: File? = null

    /** 由 Rime 启动时调用：profile 模式传 `部署/<方案目录>`，默认模式传 null。 */
    fun setStagingDir(dir: File?) {
        stagingDirOverride = dir
    }

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
