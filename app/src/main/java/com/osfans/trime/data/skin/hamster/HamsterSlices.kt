/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.osfans.trime.core.data.DataManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 元书皮肤的资源切片。
 *
 * `.cskin` 里按键的键帽不是独立图片，而是一张精灵图（如 `26anjian.png`）加一份元数据
 * （`26anjian.yaml`，给出每个切片的 rect 与 insets）。insets 的语义恰恰就是九宫格边距，
 * 所以这里按 rect 切出来、用 insets 画上.9.png 的黑色标记，写到 Rime 根目录的
 * `backgrounds/hamster-slices/`，主题里用相对路径引用即可——同文会走
 * [com.osfans.trime.util.NinePatchBitmapFactory] 读源码版九宫格。
 *
 * 切片按「源图 / 元数据的修改时间」判断缓存，换皮肤或换深浅色不会互相覆盖
 * （文件名带皮肤目录名与 sheet 名）。
 */
object HamsterSlices {
    private const val OUT_FOLDER = "hamster-slices"

    /** 一个切片的原始位置与九宫格边距（单位：像素）。 */
    private data class Slice(
        val src: Rect,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
    )

    /**
     * 把 [file] 精灵图里的 [image] 切成九宫格 PNG，返回可直接写进 `key_back_color` 的
     * 相对路径（相对 `backgrounds/`）；失败返回 null，调用方退回纯色。
     */
    fun slice(
        skinDir: File,
        dark: Boolean,
        file: String,
        image: String,
    ): String? {
        val resDir = resourcesDir(skinDir, dark) ?: return null
        val sheet = File(resDir, "$file.png")
        val meta = File(resDir, "$file.yaml")
        if (!sheet.isFile || !meta.isFile) return null

        val outName = "${skinDir.name}_${file}_$image.9.png"
        val outFile = File(DataManager.userDataDir, "backgrounds/$OUT_FOLDER/$outName")
        val fresh = outFile.isFile &&
            outFile.lastModified() >= maxOf(sheet.lastModified(), meta.lastModified())
        if (fresh) return "$OUT_FOLDER/$outName"

        val entry = runCatching { parseMeta(meta)[image] }.getOrNull() ?: return null
        val sheetBitmap = runCatching { BitmapFactory.decodeFile(sheet.absolutePath) }.getOrNull() ?: return null
        val cropped = runCatching {
            Bitmap.createBitmap(
                sheetBitmap,
                entry.src.left,
                entry.src.top,
                entry.src.width(),
                entry.src.height(),
            )
        }.getOrNull() ?: return null

        val ninePatch = buildNinePatch(cropped, entry) ?: return null
        val ok = runCatching {
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { ninePatch.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        ninePatch.recycle()
        if (!ok) {
            Timber.w("hamster: 写出切片失败 %s", outFile)
            return null
        }
        return "$OUT_FOLDER/$outName"
    }

    /**
     * 切前景图（元书的字母、图标也是精灵图）。
     *
     * 前景按原比例居中绘制，不拉伸，所以不画九宫格标记，输出普通 PNG。
     */
    fun slicePlain(
        skinDir: File,
        dark: Boolean,
        file: String,
        image: String,
    ): String? {
        val resDir = resourcesDir(skinDir, dark) ?: return null
        val sheet = File(resDir, "$file.png")
        val meta = File(resDir, "$file.yaml")
        if (!sheet.isFile || !meta.isFile) return null

        val outName = "${skinDir.name}_${file}_$image.png"
        val outFile = File(DataManager.userDataDir, "backgrounds/$OUT_FOLDER/$outName")
        val fresh = outFile.isFile &&
            outFile.lastModified() >= maxOf(sheet.lastModified(), meta.lastModified())
        if (fresh) return "$OUT_FOLDER/$outName"

        val entry = runCatching { parseMeta(meta)[image] }.getOrNull() ?: return null
        val sheetBitmap = runCatching { BitmapFactory.decodeFile(sheet.absolutePath) }.getOrNull() ?: return null
        val cropped = runCatching {
            Bitmap.createBitmap(
                sheetBitmap,
                entry.src.left,
                entry.src.top,
                entry.src.width(),
                entry.src.height(),
            )
        }.getOrNull() ?: return null

        val ok = runCatching {
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        cropped.recycle()
        return if (ok) "$OUT_FOLDER/$outName" else null
    }

    private fun resourcesDir(skinDir: File, dark: Boolean): File? {
        val candidates = buildList {
            add(File(skinDir, if (dark) "dark/resources" else "light/resources"))
            add(File(skinDir, "resources"))
            add(File(skinDir, if (dark) "light/resources" else "dark/resources"))
        }
        return candidates.firstOrNull { it.isDirectory }
    }

    /** 按 rect + insets 生成带九宫格标记的图片（四周各留 1px 给黑色标记）。 */
    private fun buildNinePatch(
        src: Bitmap,
        entry: Slice,
    ): Bitmap? {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return null

        val out = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, Rect(0, 0, w, h), Rect(1, 1, w + 1, h + 1), null)

        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        // 顶部黑线 = 水平可拉伸区域。insets 过大时退化为整条。
        val left = entry.left.coerceIn(0, w - 1)
        val right = entry.right.coerceIn(0, w - 1 - left)
        val stretchW = w - left - right
        if (stretchW > 0) {
            canvas.drawRect((1 + left).toFloat(), 0f, (1 + left + stretchW).toFloat(), 1f, paint)
        } else {
            canvas.drawRect(1f, 0f, (1 + w).toFloat(), 1f, paint)
        }

        // 左侧黑线 = 垂直可拉伸区域。
        val top = entry.top.coerceIn(0, h - 1)
        val bottom = entry.bottom.coerceIn(0, h - 1 - top)
        val stretchH = h - top - bottom
        if (stretchH > 0) {
            canvas.drawRect(0f, (1 + top).toFloat(), 1f, (1 + top + stretchH).toFloat(), paint)
        } else {
            canvas.drawRect(0f, 1f, 1f, (1 + h).toFloat(), paint)
        }

        return out
    }

    /**
     * 读元书的切片元数据。
     *
     * 文件格式固定为「图片名 → rect{x,y,width,height} / insets{top,bottom,left,right}」，
     * 层级很浅，逐行按缩进解析即可，不必为此引入 YAML 依赖。
     */
    private fun parseMeta(file: File): Map<String, Slice> {
        val result = mutableMapOf<String, Slice>()
        var name: String? = null
        var section = ""
        val nums = mutableMapOf<String, Int>()

        fun flush() {
            val key = name ?: return
            val x = nums["rect.x"] ?: 0
            val y = nums["rect.y"] ?: 0
            val w = nums["rect.width"] ?: 0
            val h = nums["rect.height"] ?: 0
            if (w > 0 && h > 0) {
                result[key] = Slice(
                    src = Rect(x, y, x + w, y + h),
                    top = nums["insets.top"] ?: 0,
                    bottom = nums["insets.bottom"] ?: 0,
                    left = nums["insets.left"] ?: 0,
                    right = nums["insets.right"] ?: 0,
                )
            }
            nums.clear()
        }

        for (raw in file.readLines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (indent == 0 && line.endsWith(":")) {
                flush()
                name = line.dropLast(1).trim()
                section = ""
                continue
            }
            if (line.endsWith(":")) {
                section = line.dropLast(1).trim()
                continue
            }
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim().toIntOrNull() ?: continue
            nums[if (section.isEmpty()) key else "$section.$key"] = value
        }
        flush()
        return result
    }
}
