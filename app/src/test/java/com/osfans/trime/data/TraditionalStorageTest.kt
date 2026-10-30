package com.osfans.trime.data

import com.osfans.trime.core.data.SafeDirectoryCopy
import com.osfans.trime.core.data.TraditionalRimeLayout
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class TraditionalStorageTest {
    @Test fun migrationPreservesPatchesLearningAndPrebuiltDictionaries() {
        val root = Files.createTempDirectory("rime-layout").toFile()
        try {
            fun seed(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
            seed("运行数据/default.custom.yaml", "runtime patch")
            seed("方案/tiger/default.custom.yaml", "package patch")
            seed("方案/tiger/tiger.schema.yaml", "schema")
            seed("方案/tiger/.prebuilt/tiger.table.bin", "dictionary")
            seed("皮肤/coffee/backgrounds/a.png", "image")
            seed("学习/tiger.userdb/data", "learned")
            seed("部署/tiger.table.bin", "compiled")
            seed("部署/flypy/flypy.table.bin", "binary-only")
            seed("运行数据/运行数据/loop", "recursive garbage")
            TraditionalRimeLayout.prepare(root)
            assertEquals("runtime patch", File(root, "default.custom.yaml").readText())
            assertEquals("package patch", File(root, "cheese/legacy/方案/tiger/default.custom.yaml").readText())
            assertTrue(File(root, "tiger.schema.yaml").isFile)
            assertTrue(File(root, "tiger.userdb/data").isFile)
            assertTrue(File(root, "build/tiger.table.bin").isFile)
            assertEquals("binary-only", File(root, "build/flypy.table.bin").readText())
            assertTrue(File(root, ".prebuilt/tiger.table.bin").isFile)
            assertTrue(File(root, "backgrounds/a.png").isFile)
            assertFalse(File(root, "运行数据").exists())
            TraditionalRimeLayout.prepare(root)
            assertEquals("runtime patch", File(root, "default.custom.yaml").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun copyingIntoSelfIsRejectedWithoutCreatingNestedTrees() {
        val root = Files.createTempDirectory("rime-copy").toFile()
        try {
            File(root, "source.yaml").writeText("source")
            try {
                SafeDirectoryCopy.copy(root, File(root, "运行数据"))
                fail("overlap accepted")
            } catch (_: IllegalArgumentException) { }
            assertFalse(File(root, "运行数据").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun existingUserDatabaseIsNeverMergedWithAnotherDatabase() {
        val root = Files.createTempDirectory("rime-db").toFile()
        try {
            File(root, "tiger.userdb").mkdirs()
            File(root, "tiger.userdb/CURRENT").writeText("existing")
            File(root, "学习/tiger.userdb").mkdirs()
            File(root, "学习/tiger.userdb/000001.log").writeText("old")
            TraditionalRimeLayout.prepare(root)
            assertFalse(File(root, "tiger.userdb/000001.log").exists())
            assertTrue(File(root, "cheese/legacy/学习/tiger.userdb/000001.log").exists())
        } finally { root.deleteRecursively() }
    }
}
