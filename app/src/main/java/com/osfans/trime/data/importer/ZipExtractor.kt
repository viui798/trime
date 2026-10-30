/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.importer

import timber.log.Timber
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 芝版同文：把 zip 解到指定目录。
 *
 * 独立出来的原因有两个：导入器要从 Uri 解，[RimeDataLayout] 要在启动时把用户直接丢进
 * 「方案」「皮肤」文件夹里的 zip 自动展开。两处共用同一套编码探测和路径校验。
 */
object ZipExtractor {
    private const val TAG = "ZipExtractor"

    /**
     * 依次尝试的字符集。
     *
     * 国内方案包常见两种坏情况：GBK 文件名不带 UTF-8 标志位、UTF-8 文件名也不带标志位。
     * 固定字符集必然有一半会抛 `MalformedInputException`（Android 的 java.util.zip 是严格解码）。
     * 按 UTF-8 → GBK → GB18030 试，第一个能解析中央目录的就是对的。
     * 带 UTF-8 标志位的条目无论传哪个字符集都按 UTF-8 处理。
     */
    private val CHARSETS: List<Charset> =
        listOf(
            Charsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("GB18030"),
        )

    private val JUNK_PREFIXES = listOf("__MACOSX/", "._")
    private val JUNK_NAMES = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

    /**
     * 别的平台的学习数据目录，解包时直接丢掉。
     *
     * `build/` 不能在这里丢：不少方案包（芝鹤就是）不带 `.dict.yaml` 源，
     * 词典只在 `build/` 下的 `.bin` 里。保留它们，布局准备时为无词典源的二进制留恢复副本。
     */
    private val JUNK_DIRS = setOf("trash", "sync")

    private val JUNK_FILES =
        setOf(
            "user.yaml",
            "installation.yaml",
            "iRime.custom.yaml",
            ".custom.yaml",
            "weasel.yaml",
            "weasel.custom.yaml",
            "squirrel.yaml",
            "squirrel.custom.yaml",
            "hamster.yaml",
            "hamster.plist",
            "keyboard_swipe.plist",
        )

    private val JUNK_SUFFIXES =
        listOf(".userdb", ".userdb.txt", ".plist", ".ico", ".kct", ".snapshot")

    const val DEFAULT_CUSTOM_FILE = "default.custom.yaml"

    data class Result(
        val files: Int,
        val skipped: Int,
        val hasDefaultCustom: Boolean,
    ) {
        val isEmpty: Boolean get() = files == 0
    }

    fun openZip(file: File): ZipFile {
        var last: Exception? = null
        for (charset in CHARSETS) {
            try {
                return ZipFile(file, charset)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("无法读取 zip")
    }

    /** 解压 [zipFile] 到 [destDir]。所有条目共用的唯一顶层目录会被剥掉。 */
    fun extract(
        zipFile: File,
        destDir: File,
    ): Result = openZip(zipFile).use { zip ->
        val entries = mutableListOf<Pair<ZipEntry, String>>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            val path = normalizeEntryPath(entry.name) ?: continue
            entries += entry to path
        }

        val prefix = commonRootPrefix(entries.map { it.second })
        var written = 0
        var skipped = 0
        var hasDefault = false

        for ((entry, rawPath) in entries) {
            val path = if (prefix != null) rawPath.removePrefix(prefix) else rawPath
            if (path.isBlank()) {
                skipped++
                continue
            }
            val target = safeResolve(destDir, path)
            if (target == null) {
                skipped++
                Timber.w("$TAG: 拒绝可疑路径 %s", entry.name)
                continue
            }
            runCatching {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                written++
                if (target.name == DEFAULT_CUSTOM_FILE) hasDefault = true
            }.onFailure {
                skipped++
                Timber.w(it, "$TAG: 解压失败 %s", entry.name)
            }
        }
        Result(written, skipped, hasDefault)
    }

    private fun normalizeEntryPath(raw: String): String? {
        val path = raw.replace('\\', '/').trim()
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return null
        if (JUNK_PREFIXES.any { path.startsWith(it) }) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        if (segments.last() in JUNK_NAMES) return null
        if (segments.any { isJunkSegment(it) }) return null
        return segments.joinToString("/")
    }

    private fun isJunkSegment(segment: String): Boolean =
        segment in JUNK_DIRS ||
            segment in JUNK_FILES ||
            JUNK_SUFFIXES.any { segment.endsWith(it) }

    /** 所有条目共用的唯一顶层目录，例如 `芝士虎整-v0.2.9/`。 */
    private fun commonRootPrefix(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        if (paths.any { !it.contains('/') }) return null
        val first = paths.first().substringBefore('/')
        return if (paths.all { it.substringBefore('/') == first }) "$first/" else null
    }

    /** 把相对路径解析到 destDir 内，越界返回 null（Zip Slip 防护）。 */
    private fun safeResolve(
        destDir: File,
        path: String,
    ): File? {
        val root = destDir.canonicalFile
        val target = File(root, path).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }
}
