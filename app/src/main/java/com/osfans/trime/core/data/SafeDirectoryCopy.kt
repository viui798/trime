/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import java.io.File

/** Copy missing files only, rejecting overlapping directory trees and symlinks. */
object SafeDirectoryCopy {
    fun copy(source: File, target: File) {
        val src = source.canonicalFile
        val dst = target.canonicalFile
        if (!src.exists() || src == dst) return
        require(!dst.path.startsWith(src.path + File.separator) && !src.path.startsWith(dst.path + File.separator)) {
            "源目录和目标目录不能互相包含"
        }
        src.walkTopDown().onEnter { dir ->
            // LevelDB directories are indivisible; never mix two databases' internal files.
            !(dir.name.endsWith(".userdb") && File(dst, dir.relativeTo(src).path).exists())
        }.forEach { file ->
            require(file.canonicalPath == file.absolutePath) { "迁移目录不能包含符号链接" }
            val out = File(dst, file.relativeTo(src).path)
            require(out.canonicalPath == out.absolutePath) { "目标目录不能包含符号链接" }
            if (file.isDirectory) check(out.mkdirs() || out.isDirectory)
            else if (!out.exists()) {
                val temporary = File.createTempFile(".cheese-copy-", ".tmp", out.parentFile)
                try {
                    file.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(temporary.length() == file.length())
                    check(temporary.renameTo(out)) { "无法保存迁移文件：$out" }
                } finally { temporary.delete() }
            }
        }
    }
}
