/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import java.io.File

/**
 * 芝版同文：把 `rime/方案` 下的每个方案包当成一个可切换的「方案目录」。
 *
 * 用户导入多个方案包后，一次只想用其中一个包里的方案。这里负责：
 * 1. 扫描 `方案/<包>/`，读出包名（芝鹤音形、芝士虎整…）；
 * 2. 第一次选中某个包时，把包内容复制到 `方案库/<包>/`，作为独立 user_data_dir；
 * 3. 之后 Rime 只看到这个包里的 schema，`学习/` 仍然是全局共享。
 *
 * 复制会占双份空间（用户已确认可接受）；包内容变化时按 mtime 重新复制。
 */
data class SchemePackage(
    val dir: File,
    val profileName: String,
    val displayName: String,
)

object SchemePackageManager {
    private const val MARKER = ".profile-source"

    /** 列出 `方案/` 下所有至少含一个 schema 的包，按显示名排序。 */
    fun listPackages(): List<SchemePackage> =
        DataManager.schemaRootDir
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.mapNotNull { dir ->
                if (schemaIds(dir).isEmpty()) {
                    null
                } else {
                    SchemePackage(
                        dir = dir,
                        profileName = dir.name,
                        displayName = displayName(dir),
                    )
                }
            }?.sortedBy { it.displayName.lowercase() }
            .orEmpty()

    /**
     * 确保 `方案库/<包>/` 存在并与 `方案/<包>/` 同步。
     *
     * 只在包内容变化时重建；会跳过 `build/`（旧编译产物）、用户词典和其他平台文件，
     * 保留 `.prebuilt/`，由 [RimeDataLayout.prepare] 在部署前放进 `build/`。
     */
    fun ensureProfile(pkg: SchemePackage): File {
        val target = File(RimeProfileManager.root, pkg.profileName)
        val marker = File(target, MARKER)
        val stamp = sourceStamp(pkg.dir)
        if (target.isDirectory &&
            marker.isFile &&
            marker.readText() == stamp &&
            schemaIds(target).isNotEmpty()
        ) {
            return target
        }
        if (target.exists() && !target.deleteRecursively()) {
            error("无法清理旧方案目录：${target.absolutePath}")
        }
        target.mkdirs()
        copyTree(pkg.dir, target)
        marker.writeText(stamp)
        return target
    }

    // ------------------------------------------------------------ 显示名

    /**
     * 优先取包内 `*说明.md` / `README.md` 的一级标题（如「芝士虎整使用与维护说明」→「芝士虎整」）；
     * 否则只有一个方案时取 schema 的 `name`（如 芝鹤音形），多个方案时取首个 schema 的 name。
     */
    private fun displayName(dir: File): String {
        docTitle(dir)?.let { return it }
        val ids = schemaIds(dir)
        if (ids.size == 1) {
            return schemaName(dir, ids.first()) ?: dir.name
        }
        val primary = primarySchemaId(dir) ?: ids.firstOrNull()
        return primary?.let { schemaName(dir, it) } ?: dir.name
    }

    private fun docTitle(dir: File): String? {
        val docs =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                ?: return null
        val doc =
            docs.firstOrNull { it.name.contains("说明") }
                ?: docs.firstOrNull { it.name.equals("README.md", ignoreCase = true) }
                ?: return null
        val heading =
            runCatching {
                doc.useLines { lines -> lines.firstOrNull { it.trimStart().startsWith("#") } }
            }.getOrNull() ?: return null
        val raw = heading.trimStart().removePrefix("#").trim()
        val cleaned =
            raw
                .replace(Regex("""(使用与维护|使用|安装|维护)?说明$"""), "")
                .trim()
        return cleaned.takeIf { it.isNotBlank() && it.length <= 30 }
    }

    private fun primarySchemaId(dir: File): String? {
        val custom = File(dir, "default.custom.yaml")
        if (!custom.isFile) return null
        val text = runCatching { custom.readText() }.getOrNull() ?: return null
        return schemaLine.find(text)?.groupValues?.getOrNull(1)
    }

    private fun schemaName(
        dir: File,
        id: String,
    ): String? {
        val schema = File(dir, "$id.schema.yaml")
        if (!schema.isFile) return null
        val text = runCatching { schema.readText() }.getOrNull() ?: return null
        return nameLine
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'')
    }

    private fun schemaIds(dir: File): List<String> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }
            ?.map { it.name.removeSuffix(".schema.yaml") }
            ?.sorted()
            .orEmpty()

    // ------------------------------------------------------------ 复制

    private fun sourceStamp(dir: File): String =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .maxOfOrNull { it.lastModified() }
            ?.toString()
            ?: "0"

    private fun copyTree(
        src: File,
        dst: File,
    ) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                if (!shouldSkip(child)) copyTree(child, File(dst, child.name))
            }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun shouldSkip(file: File): Boolean {
        val name = file.name
        if (name == "build" || name == ".zips" || name == ".git" || name == "sync") return true
        if (name == "user.yaml" || name == "installation.yaml" || name == ".DS_Store") return true
        if (name.contains(".userdb")) return true
        if (name.endsWith(".plist") || name.endsWith(".ico")) return true
        return false
    }

    private val schemaLine =
        Regex("""^\s*-\s*\{?\s*schema:\s*["']?([A-Za-z0-9_]+)""", RegexOption.MULTILINE)
    private val nameLine =
        Regex("""(?m)^\s{2}name:\s*["']?(.+?)["']?\s*(?:#.*)?$""")
}
