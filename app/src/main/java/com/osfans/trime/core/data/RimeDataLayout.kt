/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.osfans.trime.data.importer.ZipExtractor
import timber.log.Timber
import java.io.File
import java.util.UUID

/** Traditional Rime directory: sources and learning data at root, compiled files in build/. */
object RimeDataLayout {
    val root: File get() = DataManager.rimeRootDir
    val schemaDir: File get() = root
    val themeDir: File get() = root
    val runtimeDir: File get() = root
    val deployDir: File get() = DataManager.defaultStagingRoot()
    val learnDir: File get() = root
    val syncDir: File get() = File(root, "sync")

    fun ensureLayout() {
        TraditionalRimeLayout.prepare(root)
        check(deployDir.mkdirs() || deployDir.isDirectory)
    }

    /** No source mirroring: librime reads the same files the user edits. */
    fun prepare() {
        ensureLayout()
        root.listFiles().orEmpty().filter { it.isFile && it.extension.equals("zip", true) }.forEach { zip ->
            runCatching {
                val result = ZipExtractor.extract(zip, root)
                check(result.files > 0) { "压缩包中没有可导入文件" }
                val archive = File(root, "cheese/zips").also { check(it.mkdirs() || it.isDirectory) }
                var target = File(archive, zip.name)
                if (target.exists()) target = File(archive, "${UUID.randomUUID()}-${zip.name}")
                check(zip.renameTo(target)) { "无法归档已导入的压缩包" }
            }.onFailure { Timber.w(it, "展开 %s 失败", zip.name) }
        }
        val active = RimeProfileManager.currentDir()
        val staging = RimeProfileManager.stagingDir()
        preserveBinaryOnlyDictionaries(active, staging)
        File(active, ".prebuilt").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "bin" }
            .forEach { bin ->
                val target = File(staging, bin.name)
                if (!target.exists()) bin.copyTo(target)
            }
        if (active.canonicalFile != root.canonicalFile) {
            root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".trime.yaml") }.forEach { source ->
                val target = File(DataManager.sharedDataDir, source.name)
                if (!target.exists() || target.length() != source.length() || target.lastModified() != source.lastModified()) {
                    source.copyTo(target, overwrite = true)
                    target.setLastModified(source.lastModified())
                }
            }
        }
        ensureSyncDirConfigured(active)
    }

    /** Keep dictionaries shipped only as binaries recoverable after clearing build/. */
    private fun preserveBinaryOnlyDictionaries(source: File, staging: File) {
        staging.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }.forEach { bin ->
            val id = bin.name.removeSuffix(".bin").removeSuffix(".table").removeSuffix(".reverse").removeSuffix(".prism")
            if (File(source, "$id.dict.yaml").exists() ||
                (bin.name.endsWith(".prism.bin") && File(source, "$id.schema.yaml").exists())) return@forEach
            val backup = File(source, ".prebuilt/${bin.name}")
            if (!backup.exists()) {
                check(backup.parentFile!!.mkdirs() || backup.parentFile!!.isDirectory)
                bin.copyTo(backup)
            }
        }
    }

    fun deployBytes(): Long = sizeOf(deployDir)

    fun clearDeployCache(): Boolean {
        val active = RimeProfileManager.currentDir()
        preserveBinaryOnlyDictionaries(root, deployDir)
        if (active.canonicalFile != root.canonicalFile) preserveBinaryOnlyDictionaries(active, RimeProfileManager.stagingDir())
        deployDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return deployDir.listFiles()?.isEmpty() != false
    }

    fun ensureSyncDirConfigured(targetDir: File = runtimeDir) {
        check(syncDir.mkdirs() || syncDir.isDirectory)
        val file = File(targetDir, "installation.yaml")
        val body = (if (file.isFile) file.readLines() else emptyList())
            .filterNot { it.trimStart().startsWith("sync_dir:") }.toMutableList()
        if (body.none { it.trimStart().startsWith("installation_id:") }) body += "installation_id: ${UUID.randomUUID()}"
        val quoted = syncDir.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        body += "sync_dir: \"$quoted\""
        check(targetDir.mkdirs() || targetDir.isDirectory)
        file.writeText(body.joinToString("\n") + "\n")
    }

    fun listSchemaFiles(): List<File> = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".schema.yaml") }

    fun listSchemaIds(): List<String> = listSchemaFiles().map { it.name.removeSuffix(".schema.yaml") }.sorted()

    fun listUserDictIds(): List<String> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.endsWith(".userdb") }.map { it.name.removeSuffix(".userdb") }.sorted()

    fun learnBytes(): Long = root.listFiles().orEmpty()
        .filter { it.name.endsWith(".userdb") || it.name == "sync" }.sumOf(::sizeOf)

    private fun sizeOf(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
