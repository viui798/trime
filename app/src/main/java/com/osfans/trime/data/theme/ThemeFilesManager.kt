/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.get
import com.osfans.trime.util.string
import timber.log.Timber
import java.io.File

object ThemeFilesManager {
    private data class Listing(val stamp: List<Triple<String, Long, Long>>, val items: List<ThemeItem>)
    private val listings = java.util.concurrent.ConcurrentHashMap<String, Listing>()

    fun listThemes(dir: File): MutableList<ThemeItem> {
        val stamp = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
            .sortedBy { it.name }.map { Triple(it.name, it.lastModified(), it.length()) }
        listings[dir.absolutePath]?.takeIf { it.stamp == stamp }?.let { return it.items.toMutableList() }

        val files = dir.listFiles { _, name -> name.endsWith("trime.yaml") } ?: return mutableListOf()
        val items = files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val configId = file.nameWithoutExtension
                val name = readSourceName(configId, file)
                    ?: readDeployedName(configId)
                    ?: configId.removeSuffix(".trime")
                ThemeItem(configId, name)
            }.toMutableList()
        listings[dir.absolutePath] = Listing(stamp, items)
        return items
    }

    /**
     * Reads the theme name from its source file, expanding the supported DSL
     * subset so a name provided by an `__include`d node is resolved too.
     */
    private fun readSourceName(configId: String, file: File): String? = runCatching { ThemeLoader.loadSourceNode(configId, file)?.get("name")?.string }
        .getOrElse { e ->
            Timber.w("Failed to decode theme file ${file.absolutePath}: ${e.message}")
            null
        }

    /** Reads the theme name from the deployed artifact, when it exists. */
    private fun readDeployedName(configId: String): String? {
        val file = File(DataManager.resolveDeployedResourcePath(configId))
        if (!file.isFile) return null
        return runCatching { ThemeYaml.parser.parseToYamlNode(file.readText())["name"]?.string }
            .getOrElse { e ->
                Timber.w("Failed to read deployed theme ${file.absolutePath}: ${e.message}")
                null
            }
    }
}
