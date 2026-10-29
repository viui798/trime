/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import com.osfans.trime.core.data.DataManager
import java.io.File

/**
 * 芝版同文：快捷短语。
 *
 * 直接读写 Rime 用户目录下的 `custom_phrase.txt`，格式为：
 * ```
 * 词语<TAB>编码<TAB>权重
 * ```
 * 编码可以留空，表示该词以任意编码参与造词。
 * 写入后需要重新部署才会生效。
 */
object PhraseManager {
    const val FILE_NAME = "custom_phrase.txt"

    private const val HEADER = """# Rime table
# 同文输入法芝士 · 快捷短语
# 格式：词语<TAB>编码<TAB>权重（编码可留空）
"""

    data class Phrase(
        val text: String,
        val code: String = "",
        val weight: Int = 1,
    ) {
        fun toLine(): String = "$text\t$code\t$weight"
    }

    val file: File
        get() = File(DataManager.userDataDir, FILE_NAME)

    fun load(): List<Phrase> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    val text = parts.getOrNull(0)?.trim().orEmpty()
                    if (text.isEmpty()) {
                        return@mapNotNull null
                    }
                    Phrase(
                        text = text,
                        code = parts.getOrNull(1)?.trim().orEmpty(),
                        weight = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1,
                    )
                }.toList()
        }.getOrDefault(emptyList())
    }

    fun save(phrases: List<Phrase>): Boolean =
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    appendLine(HEADER)
                    phrases.forEach { appendLine(it.toLine()) }
                },
            )
            true
        }.getOrDefault(false)
}
