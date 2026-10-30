/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 芝版同文：方案与皮肤导入器。
 *
 * 支持两类输入：
 * - **zip 包**：交给 [ZipExtractor] 解到 [destDir]，自动剥掉唯一的顶层目录、跳过垃圾文件
 * - **散装文件**：多选 `.yaml` / `.dict.yaml` / `.lua` / `.trime.yaml` 直接落到 [destDir]
 *
 * [destDir] 默认就是 Rime 用户目录，方案、皮肤直接解到根目录，不再镜像复制。
 */
object RimeResourceImporter {
    private const val TAG = "RimeResourceImporter"

    const val DEFAULT_CUSTOM_FILE = ZipExtractor.DEFAULT_CUSTOM_FILE

    data class Result(
        /** 实际写盘的文件数。 */
        val files: Int,
        /** 被跳过的条目数（垃圾文件、非法路径）。 */
        val skipped: Int,
        /** 包内是否自带了 default.custom.yaml（自带说明方案列表由包决定）。 */
        val hasDefaultCustom: Boolean,
    ) {
        val isEmpty: Boolean get() = files == 0
    }

    /**
     * @param flat true 时把 zip 直接解到 [destDir]（方案库里的完整 Rime 目录用），
     *   false 时一包一个子文件夹（旧版「方案」「皮肤」用）。
     */
    suspend fun import(
        context: Context,
        uris: List<Uri>,
        destDir: File,
        flat: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        var files = 0
        var skipped = 0
        var hasDefaultCustom = false

        destDir.mkdirs()

        for (uri in uris) {
            val name = queryDisplayName(context, uri) ?: continue
            val isZip = name.endsWith(".zip", ignoreCase = true) || isZipMagic(context, uri)
            runCatching {
                if (isZip) {
                    // 旧版：每个包解到自己的同名子文件夹，避免多个包的根文件互相覆盖。
                    // 方案库：直接解到方案目录根，那个目录本身就是 Rime 的 user_data_dir。
                    val folder = if (flat || destDir.canonicalFile == com.osfans.trime.core.data.DataManager.rimeRootDir.canonicalFile) destDir else File(destDir, packageFolderName(name))
                    val result = importZip(context, uri, folder)
                    files += result.files
                    skipped += result.skipped
                    hasDefaultCustom = hasDefaultCustom || result.hasDefaultCustom
                } else {
                    if (importSingle(context, uri, name, destDir)) files++ else skipped++
                }
            }.onFailure {
                skipped++
                Timber.w(it, "导入失败: %s", name)
            }
        }

        Result(files = files, skipped = skipped, hasDefaultCustom = hasDefaultCustom)
    }

    /** `芝士虎-v0.2.9.zip` → `芝士虎-v0.2.9`，顺带挡掉路径分隔符。 */
    private fun packageFolderName(displayName: String): String =
        displayName
            .substringBeforeLast('.', displayName)
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "imported" }

    private fun importZip(
        context: Context,
        uri: Uri,
        destDir: File,
    ): ZipExtractor.Result {
        // ZipFile 需要真实文件，先落到缓存
        val temp = File.createTempFile("rime-import-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return ZipExtractor.Result(0, 1, false)
            return ZipExtractor.extract(temp, destDir)
        } finally {
            temp.delete()
        }
    }

    private fun importSingle(
        context: Context,
        uri: Uri,
        name: String,
        destDir: File,
    ): Boolean {
        val fileName = name.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val target = File(destDir, fileName)
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun queryDisplayName(
        context: Context,
        uri: Uri,
    ): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment

    private fun isZipMagic(
        context: Context,
        uri: Uri,
    ): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)
}
