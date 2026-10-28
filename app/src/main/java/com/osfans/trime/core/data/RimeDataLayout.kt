/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import com.osfans.trime.data.importer.ZipExtractor
import com.osfans.trime.util.FileUtils
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * 芝版同文：用户数据目录的分层布局。
 *
 * ```
 * files/rime/
 * ├── 方案/         用户可见。一包一个文件夹，只放方案源文件
 * ├── 皮肤/         用户可见。一包一个文件夹，只放皮肤源文件
 * ├── 部署/         Rime 的编译产物（staging_dir），可单独清理
 * ├── 学习/         用户词典（*.userdb）与同步快照，可单独备份
 * └── 运行数据/     Rime 真正的 user_data_dir：摊平后的方案源 + 运行状态
 * ```
 *
 * **为什么要有「运行数据」这一层**：librime 找方案、找词库都是非递归的
 * `fs::directory_iterator`，`SchemaUpdate` 更是直接拼 `<schema_id>.schema.yaml`
 * 再交给 `ResourceResolver` 去数据目录根上找。子目录里的文件它完全看不见。
 * Trime 自己的主题扫描（`ThemeFilesManager`）同理，只扫数据目录根。
 *
 * 所以「方案」「皮肤」只是**源**，默认模式下 [mirror] 会把它们摊平复制到「运行数据」。
 * 摊平规则：**剥掉包文件夹这一层**，再往下的结构原样保留——
 * `方案/某方案/dicts/a.dict.yaml` → `运行数据/dicts/a.dict.yaml`，
 * 这样包里的相对引用（`dicts/…`、`backgrounds/…`）不会断。
 *
 * **方案包不用复制**：v16 起选中「方案/<包>/」里的包时，Rime 的 `user_data_dir`
 * 直接指向包目录（见 [RimeProfileManager]），不再复制到方案库，也就没有双份空间；
 * 摊平只发生在「默认（运行数据）」模式（散装文件）下。
 *
 * 部署产物和学习数据不再跟方案源混在「运行数据」里：
 * - `staging_dir` 由 JNI 传给 librime：默认模式指向 [deployDir]，
 *   直连方案包时指向 `部署/<包名>/`（编译产物按包分开，包目录保持纯源）
 * - `userdb_dir` 由 JNI 传给 librime，指向 [learnDir]
 * - `sync_dir` 写在 `installation.yaml` 里，指向 [syncDir]
 */
object RimeDataLayout {
    const val DIR_SCHEMA = "方案"
    const val DIR_THEME = "皮肤"
    const val DIR_RUNTIME = "运行数据"
    const val DIR_DEPLOY = "部署"
    const val DIR_LEARN = "学习"

    /** 布局版本标记。改布局时换名字即可让老用户重新迁移。 */
    private const val LAYOUT_MARKER = ".layout-split-2"

    /** 记录上次摊平写入的相对路径，用来清理已经删掉的源文件。 */
    private const val MIRROR_MANIFEST = ".mirror-manifest"

    /** 记录 zip 展开时的 size:mtime，避免每次启动重复解压。 */
    private const val ZIP_STAMP = ".zip-source"

    /** 展开成功后把原始 zip 收进这个隐藏目录，避免「zip + 同名文件夹」两份摆在用户面前。 */
    private const val ZIP_ARCHIVE_DIR = ".zips"

    /**
     * 包内保存预编译词典的隐藏目录。
     *
     * 芝鹤这类包不带 `.dict.yaml` 源，词典只在 `build/` 下的 `.bin` 里；`build/` 其余内容
     * 是别的平台的部署产物。导入时把 `.bin` 收进这里，部署前再放进「部署」。
     */
    private const val PREBUILT_DIR = ".prebuilt"

    private const val INSTALLATION_FILE = "installation.yaml"
    private const val SYNC_DIR_NAME = "sync"

    /**
     * 方案包里经常混入别的平台的部署产物和学习数据。
     * 这些要么是编译缓存（Android 上必须重编），要么是作者自己的词频，
     * 一律不进入「运行数据」，也不留在包里。
     */
    private val JUNK_DIRS = setOf("build", "trash", "sync")

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
            ".DS_Store",
            "Thumbs.db",
            "desktop.ini",
        )

    val root: File get() = DataManager.rimeRootDir
    val schemaDir: File get() = DataManager.schemaRootDir
    val themeDir: File get() = DataManager.themeRootDir
    val runtimeDir: File get() = DataManager.userDataDir
    val deployDir: File get() = DataManager.deployDir
    val learnDir: File get() = DataManager.learnDir
    val syncDir: File get() = File(learnDir, SYNC_DIR_NAME)

    data class MirrorResult(
        val copied: Int,
        val removed: Int,
        /** 源文件总数。 */
        val total: Int,
    )

    /**
     * 应用启动时调用：只建目录 + 迁移老布局，不做摊平。
     *
     * 摊平要遍历文件，放到 Rime 启动时做（[prepare]），避免拖慢冷启动。
     * 但目录本身必须在用户打开文件管理器时就已经在，否则「往方案文件夹丢东西」这件事无从谈起。
     */
    fun ensureLayout() {
        schemaDir.mkdirs()
        themeDir.mkdirs()
        deployDir.mkdirs()
        learnDir.mkdirs()
        syncDir.mkdirs()
        // 方案库：每套方案一个完整的 Rime user_data_dir。
        RimeProfileManager.root.mkdirs()
        migrateIfNeeded()
    }

    /**
     * 启动 Rime 前调用：迁移老布局 → 展开用户直接丢进来的 zip → 清掉包内脏数据
     * → 摊平给 Rime → 把同步目录写进 installation.yaml。
     *
     * 选中「方案库」里的方案时不做摊平：那个目录本身就是完整的 user_data_dir，
     * 只补预编译词典和同步目录。
     *
     * 必须在 `Rime.startup()` 之前跑完，否则 Rime 看到的是上一次的文件，方案列表也会算错。
     */
    fun prepare() {
        ensureLayout()
        if (RimeProfileManager.isProfile()) {
            // 直连/方案目录模式下也展开用户丢进来的 zip，随后 listPackages 就能看到新包
            expandUserZips()
            val profile = RimeProfileManager.currentDir()
            installPrebuiltBins(profile, RimeProfileManager.stagingDir())
            ensureSyncDirConfigured(profile)
            return
        }
        expandUserZips()
        sanitizePackages()
        // 先把包内预编译词典放进「部署」，否则没有 .dict.yaml 源的方案编不出来。
        installPrebuiltBins()
        sanitizeDeployDir()
        val result = mirror()
        if (result.copied > 0 || result.removed > 0) {
            Timber.i("执行数据摊平：写入 ${result.copied}，清理 ${result.removed}，共 ${result.total}")
        }
        ensureSyncDirConfigured()
    }

    // ------------------------------------------------------------ 迁移

    /**
     * 把老版本布局搬到新布局，只在标记文件不存在时执行一次。
     *
     * 1. 平铺的 rime 根目录条目 → `运行数据/`（v1 布局）
     * 2. `运行数据/build` → `部署/`
     * 3. `运行数据` 下的 `*.userdb` 与 `sync` → `学习/`
     */
    private fun migrateIfNeeded() {
        val marker = File(runtimeDir, LAYOUT_MARKER)
        if (marker.exists()) return

        val keep =
            setOf(
                DIR_SCHEMA,
                DIR_THEME,
                DIR_RUNTIME,
                DIR_DEPLOY,
                DIR_LEARN,
                RimeProfileManager.PROFILE_ROOT_NAME,
            )
        root.listFiles()?.forEach { entry ->
            if (entry.name in keep) return@forEach
            moveInto(entry, File(runtimeDir, entry.name))
        }

        val oldBuild = File(runtimeDir, "build")
        if (oldBuild.isDirectory) {
            oldBuild.listFiles()?.forEach { moveInto(it, File(deployDir, it.name)) }
            oldBuild.delete()
        }

        runtimeDir.listFiles()?.forEach { entry ->
            // *.userdb（LevelDB 目录）、*.userdb.kct / *.userdb.txt（旧格式）都要搬走
            if (entry.name.contains(".userdb") || entry.name == SYNC_DIR_NAME) {
                moveInto(entry, File(learnDir, entry.name))
            }
        }

        runCatching { marker.writeText("2") }
        Timber.i("已迁移到 方案/皮肤/部署/学习/运行数据 布局")
    }

    /** 把 [src] 搬到 [dest]（同一分区用 rename，失败退回复制）。 */
    private fun moveInto(
        src: File,
        dest: File,
    ) {
        if (!src.exists()) return
        if (dest.exists()) FileUtils.delete(dest)
        runCatching {
            if (src.renameTo(dest)) return
            src.copyRecursively(dest, overwrite = true)
            FileUtils.delete(src)
        }.onFailure { Timber.w(it, "迁移失败: %s", src.name) }
    }

    // ------------------------------------------------------ zip 自动展开

    /**
     * 用户可能直接把 zip 丢进「方案」或「皮肤」文件夹。
     * 这里把它展开到同名子文件夹，并用 size:mtime 记录，避免重复解压。
     * 展开成功后把 zip 收进 `.zips/`，目录里只留包文件夹。
     */
    private fun expandUserZips() {
        listOf(schemaDir, themeDir).forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
                ?.forEach { zip ->
                    runCatching {
                        expandOne(zip, File(dir, zip.nameWithoutExtension))
                        archiveZip(zip, dir)
                    }.onFailure { Timber.w(it, "展开 %s 失败", zip.name) }
                }
        }
    }

    private fun archiveZip(
        zip: File,
        dir: File,
    ) {
        val archive = File(dir, ZIP_ARCHIVE_DIR).also { it.mkdirs() }
        val target = File(archive, zip.name)
        if (target.exists()) FileUtils.delete(target)
        runCatching {
            if (zip.renameTo(target)) return
            zip.copyTo(target, overwrite = true)
            zip.delete()
        }.onFailure { Timber.w(it, "归档 %s 失败", zip.name) }
    }

    private fun expandOne(
        zip: File,
        out: File,
    ) {
        val stamp = File(out, ZIP_STAMP)
        val signature = "${zip.length()}:${zip.lastModified()}"
        if (out.isDirectory && stamp.isFile && runCatching { stamp.readText() }.getOrNull() == signature) return
        if (out.exists()) FileUtils.delete(out)
        out.mkdirs()
        val result = ZipExtractor.extract(zip, out)
        runCatching { stamp.writeText(signature) }
        Timber.i("展开 ${zip.name}：${result.files} 个文件，跳过 ${result.skipped}")
    }

    // -------------------------------------------------- 包内脏数据清理

    /** 把「方案」「皮肤」里的每个包都清一遍，保证包就是纯源。 */
    private fun sanitizePackages() {
        // 正在被直连使用的包是 Rime 的工作目录（user.yaml / installation.yaml 是运行时文件），不能清
        val active = RimeProfileManager.activeLinkedPackage()
        listOf(schemaDir, themeDir).forEach { dir ->
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { pkg ->
                if (pkg == active) return@forEach
                runCatching { sanitizePackage(pkg) }
                    .onFailure { Timber.w(it, "清理包失败: %s", pkg.name) }
            }
        }
    }

    private fun sanitizePackage(pkg: File) {
        // 先把 build/ 下的 .bin 救出来，再删 build/。芝鹤这类包没有 .dict.yaml 源，
        // 词典只存在于 build/ 里，丢了就再也编不出来。
        rescuePrebuiltBins(pkg)
        pkg.listFiles()?.forEach { child ->
            when {
                child.name == PREBUILT_DIR -> Unit

                child.isDirectory && (child.name in JUNK_DIRS || child.name.endsWith(".userdb")) -> {
                    FileUtils.delete(child)
                }

                child.isDirectory -> sanitizePackage(child)

                isJunk(child.name) -> FileUtils.delete(child)
            }
        }
    }

    private fun rescuePrebuiltBins(pkg: File) {
        val build = File(pkg, "build")
        if (!build.isDirectory) return
        val prebuilt = File(pkg, PREBUILT_DIR).also { it.mkdirs() }
        build.listFiles { f -> f.isFile && f.name.endsWith(".bin", ignoreCase = true) }?.forEach { bin ->
            val target = File(prebuilt, bin.name)
            if (target.exists()) FileUtils.delete(target)
            runCatching { bin.copyTo(target, overwrite = true) }
                .onFailure { Timber.w(it, "保存预编译词典失败: %s", bin.name) }
        }
    }

    /**
     * 把包内 `.prebuilt/` 下的 `.bin` 放进「部署」。
     *
     * Rime 的 staging_dir 就是「部署」；没有 `.dict.yaml` 源的方案只能靠这些
     * 预编译的 table/prism/reverse 词典。每次启动都对齐一遍，`部署/` 被删掉也能恢复。
     */
    private fun installPrebuiltBins() {
        schemaDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { pkg ->
            installPrebuiltBins(pkg, deployDir)
        }
    }

    /** 把 [pkgRoot] 下 `.prebuilt/` 里的 `.bin` 放进 [targetDir]；方案目录模式用。 */
    private fun installPrebuiltBins(
        pkgRoot: File,
        targetDir: File,
    ) {
        val prebuilt = File(pkgRoot, PREBUILT_DIR)
        if (!prebuilt.isDirectory) return
        targetDir.mkdirs()
        prebuilt.listFiles { f -> f.isFile && f.name.endsWith(".bin", ignoreCase = true) }?.forEach { bin ->
            val target = File(targetDir, bin.name)
            val unchanged =
                target.isFile &&
                    target.length() == bin.length() &&
                    target.lastModified() == bin.lastModified()
            if (unchanged) return@forEach
            runCatching {
                bin.copyTo(target, overwrite = true)
                target.setLastModified(bin.lastModified())
            }.onFailure { Timber.w(it, "安装预编译词典失败: %s", bin.name) }
        }
    }

    /**
     * 老布局的 `运行数据/build` 是从方案包原样搬过来的，里面常混着别的平台的
     * `weasel.yaml`、`hamster.plist` 之类。它们留在「部署」里只会让目录变脏，
     * Rime 也用不到，直接清掉。
     */
    private fun sanitizeDeployDir() {
        deployDir.listFiles()?.forEach { child ->
            if (child.isFile && isJunk(child.name)) {
                FileUtils.delete(child)
            }
        }
    }

    // ------------------------------------------------------------ 摊平

    /**
     * 把「方案」「皮肤」摊平到「运行数据」。
     *
     * 规则：**剥掉包文件夹这一层**。`源/包名/a.yaml` 落到 `运行数据/a.yaml`，
     * `源/包名/dicts/b.yaml` 落到 `运行数据/dicts/b.yaml`。
     * 直接放在源目录根下的文件也照原样落到根。
     *
     * 通过清单文件清理源里已经删掉的条目，不会碰 Rime 自己生成的东西
     * （`user.yaml`、`installation.yaml`、我们生成的 `default.custom.yaml` 都不在清单里）。
     */
    fun mirror(): MirrorResult {
        val written = LinkedHashSet<String>()
        var copied = 0
        var total = 0

        for (source in listOf(schemaDir, themeDir)) {
            val isTheme = source == themeDir
            collectEntries(source).forEach { (file, rel) ->
                if (isJunkPath(rel)) return@forEach
                // 皮肤的 fonts/backgrounds 由 ThemeAssets 直读包目录，不再复制；
                // 主题 yaml 仍然摊平（很小，且 ThemeLoader 的 fallback 要用）。
                if (isTheme && isThemeAsset(rel)) return@forEach
                total++
                written += rel
                val target = File(runtimeDir, rel)
                val unchanged =
                    target.isFile &&
                        target.length() == file.length() &&
                        target.lastModified() == file.lastModified()
                if (unchanged) return@forEach
                runCatching {
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                    target.setLastModified(file.lastModified())
                    copied++
                }.onFailure { Timber.w(it, "摊平失败: %s", rel) }
            }
        }

        val manifest = File(runtimeDir, MIRROR_MANIFEST)
        val previous =
            if (manifest.isFile) {
                runCatching { manifest.readLines().filter { it.isNotBlank() }.toSet() }
                    .getOrDefault(emptySet())
            } else {
                emptySet()
            }
        var removed = 0
        (previous - written).forEach { rel ->
            val stale = File(runtimeDir, rel)
            if (stale.isFile && FileUtils.delete(stale).isSuccess) removed++
        }
        runCatching { manifest.writeText(written.joinToString("\n")) }
        // 皮肤资源不再镜像后，旧版本留下的空 fonts/backgrounds 目录顺手清掉。
        listOf("backgrounds", "fonts").forEach { name ->
            File(runtimeDir, name).takeIf { it.isDirectory }?.let(::deleteEmptyDirs)
        }

        return MirrorResult(copied = copied, removed = removed, total = total)
    }

    private fun deleteEmptyDirs(dir: File) {
        dir.listFiles()?.forEach { child -> if (child.isDirectory) deleteEmptyDirs(child) }
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    /** 源目录里的文件 → 摊平后的相对路径。 */
    private fun collectEntries(source: File): List<Pair<File, String>> {
        val result = mutableListOf<Pair<File, String>>()
        source.listFiles()?.forEach { child ->
            if (child.name.startsWith(".")) return@forEach
            if (child.isFile) {
                result += child to child.name
            } else {
                child
                    .walkTopDown()
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { file ->
                        val rel = file.toRelativeString(child).replace('\\', '/')
                        if (rel.isNotEmpty()) result += file to rel
                    }
            }
        }
        return result
    }

    // ------------------------------------------------------ 同步目录配置

    /**
     * librime 的 `sync_dir` 从 `installation.yaml` 读；不写就会默认成
     * `运行数据/sync`。这里强制指到 `学习/sync`，让同步快照跟用户词典待在一起。
     */
    fun ensureSyncDirConfigured(targetDir: File = runtimeDir) {
        syncDir.mkdirs()
        val file = File(targetDir, INSTALLATION_FILE)
        val lines =
            if (file.isFile) {
                runCatching { file.readLines() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val body = lines.filterNot { it.trimStart().startsWith("sync_dir:") }.toMutableList()
        if (body.none { it.trimStart().startsWith("installation_id:") }) {
            body += "installation_id: ${UUID.randomUUID()}"
        }
        val quoted = syncDir.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        body += "sync_dir: \"$quoted\""
        runCatching {
            targetDir.mkdirs()
            file.writeText(body.joinToString("\n") + "\n")
        }.onFailure { Timber.w(it, "写入 installation.yaml 失败") }
    }

    // ------------------------------------------------------------ 查询

    /** 「方案」文件夹里的方案 id（递归，含包内子目录）。 */
    fun listSchemaIds(): List<String> {
        val ids = sortedSetOf<String>()
        schemaDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".schema.yaml") }
            .filterNot { isJunkPath(it.toRelativeString(schemaDir)) }
            .forEach { ids += it.name.removeSuffix(".schema.yaml") }
        return ids.toList()
    }

    /** 「方案」文件夹里所有方案文件。 */
    fun listSchemaFiles(): List<File> =
        schemaDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".schema.yaml") }
            .filterNot { isJunkPath(it.toRelativeString(schemaDir)) }
            .toList()

    /** 「学习」文件夹里的用户词典 id（`xxx.userdb` → `xxx`）。 */
    fun listUserDictIds(): List<String> =
        learnDir
            .listFiles { f -> f.isDirectory && f.name.endsWith(".userdb") }
            ?.map { it.name.removeSuffix(".userdb") }
            ?.sorted()
            .orEmpty()

    /** 「部署」目录占用的字节数。 */
    fun deployBytes(): Long = sizeOf(deployDir)

    /** 「学习」目录占用的字节数。 */
    fun learnBytes(): Long = sizeOf(learnDir)

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) {
            0L
        } else {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    // ------------------------------------------------------------ 垃圾判定

    private fun isJunk(name: String): Boolean =
        name in JUNK_FILES ||
            name.endsWith(".userdb") ||
            name.endsWith(".userdb.txt") ||
            name.endsWith(".plist") ||
            name.endsWith(".ico") ||
            name.endsWith(".kct") ||
            name.endsWith(".snapshot") ||
            name.endsWith(".bin.old")

    /** 皮肤包里的字体/背景不摊平，由 ThemeAssets 直读包目录。 */
    private fun isThemeAsset(rel: String): Boolean {
        val first = rel.substringBefore('/').lowercase()
        return first == "backgrounds" || first == "fonts"
    }

    private fun isJunkPath(rel: String): Boolean =
        rel
            .split('/')
            .any { segment -> segment in JUNK_DIRS || segment == PREBUILT_DIR || isJunk(segment) }
}
