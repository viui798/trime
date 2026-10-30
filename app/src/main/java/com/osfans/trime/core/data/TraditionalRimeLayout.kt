/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import java.io.File

/** One native Rime directory. Legacy sources survive in cheese/legacy on conflicts. */
object TraditionalRimeLayout {
    private val reserved = setOf("方案", "皮肤", "运行数据", "部署", "学习", "方案库", "cheese")

    @Synchronized
    fun prepare(root: File) {
        check(root.mkdirs() || root.isDirectory) { "无法创建 Rime 目录：$root" }
        val marker = File(root, ".traditional-layout-v1")
        if (marker.exists()) return
        val legacy = File(root, "cheese/legacy")
        // Move rather than duplicate large dictionaries. Existing root files win.
        fun merge(source: File, target: File) {
            if (!source.exists()) return
            check(!target.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
                "目标目录不能位于源目录内"
            }
            if (!target.exists()) {
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                check(source.renameTo(target)) { "无法迁移 $source 到 $target" }
            } else if (source.isDirectory && target.isDirectory && !source.name.endsWith(".userdb")) {
                source.listFiles().orEmpty().forEach { merge(it, File(target, it.name)) }
            }
        }
        fun moveContents(source: File, target: File, skipReserved: Boolean = false) {
            source.listFiles().orEmpty().filterNot {
                skipReserved && (it.name in reserved || it.name.startsWith(".mirror") || it.name.startsWith(".layout"))
            }.forEach { merge(it, File(target, it.name)) }
        }
        // Preserve the currently deployed runtime's patches before merging package sources.
        moveContents(File(root, "运行数据"), root, true)
        listOf("方案", "皮肤").forEach { category ->
            File(root, category).listFiles().orEmpty().forEach { entry ->
                if (entry.isDirectory && !entry.name.startsWith(".")) moveContents(entry, root)
                else if (entry.isFile && !entry.name.startsWith(".")) merge(entry, File(root, entry.name))
            }
        }
        moveContents(File(root, "学习"), root)
        moveContents(File(root, "部署"), File(root, "build"))
        // Older linked profiles stored their only binary dictionaries in 部署/<package>/.
        File(root, "build").listFiles().orEmpty().filter { it.isDirectory }.forEach { packageBuild ->
            packageBuild.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }.forEach { bin ->
                merge(bin, File(root, "build/${bin.name}"))
            }
        }
        // Keep unmerged files for recovery; never traverse them as native Rime sources.
        listOf("方案", "皮肤", "运行数据", "部署", "学习").forEach { name ->
            val old = File(root, name)
            if (old.exists()) merge(old, File(legacy, name))
        }
        marker.writeText("traditional Rime layout\n")
    }
}
