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
 * 2. 选中某个包时，Rime 的 `user_data_dir` **直接指向包目录**（零复制，
 *    见 [RimeProfileManager]）；编译产物放 `部署/<包名>/`。
 *
 * 包目录里会被 Rime 写入 `user.yaml` / `installation.yaml` 两个运行时小文件，
 * 清理逻辑（[RimeDataLayout]）对当前选中的包放行。
 */
data class SchemePackage(
    val dir: File,
    val profileName: String,
    val displayName: String,
) {
    override fun toString(): String = displayName
}

object SchemePackageManager {
    /** v15 复制方案留下的标记文件；迁移逻辑用它识别可回收的旧复制。 */
    const val COPY_MARKER = ".profile-source"

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
     * 确保方案包可直接作为 user_data_dir 使用。
     *
     * v16 起不再复制包内容：包目录本身就是 Rime 的工作目录，这里只校验目录
     * 仍然存在且带方案。返回包目录。
     */
    fun ensureProfile(pkg: SchemePackage): File {
        if (!pkg.dir.isDirectory || schemaIds(pkg.dir).isEmpty()) {
            error("方案包不存在或没有方案：${pkg.dir.absolutePath}")
        }
        return pkg.dir
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

    internal fun schemaIds(dir: File): List<String> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }
            ?.map { it.name.removeSuffix(".schema.yaml") }
            ?.sorted()
            .orEmpty()

    private val schemaLine =
        Regex("""^\s*-\s*\{?\s*schema:\s*["']?([A-Za-z0-9_]+)""", RegexOption.MULTILINE)
    private val nameLine =
        Regex("""(?m)^\s{2}name:\s*["']?(.+?)["']?\s*(?:#.*)?$""")
}
