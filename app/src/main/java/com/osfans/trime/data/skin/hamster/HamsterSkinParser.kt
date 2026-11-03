/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File

/**
 * 元书（Hamster）皮肤解析。
 *
 * `.cskin` 是 zip：`config.yaml` 声明每种键盘在什么设备/方向用哪个文件，
 * `light/` 与 `dark/` 各放一份编译好的键盘定义（JSON 的超集，可直接当 JSON 读），
 * 同目录 `resources/` 放精灵图与切片信息。
 *
 * 这里只做「按它的规则读出来」：布局是若干 HStack 行，每行的 Cell 引用一个按钮，
 * 按钮再通过名字引用样式（背景 / 前景 / 长按提示）。解析结果交给
 * [HamsterThemeBuilder] 落成同文的 [Theme]。
 */
object HamsterSkinParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一个按钮解析后需要的全部信息。 */
    data class HamsterKey(
        val label: String = "",
        val widthPercent: Float = 0f,
        val click: String? = null,
        val swipeUp: String? = null,
        val swipeDown: String? = null,
        val textColor: String? = null,
        val textSize: Float = 0f,
        val backColor: String? = null,
        val backColorPressed: String? = null,
        val cornerRadius: Float = -1f,
    )

    data class HamsterRows(
        val name: String,
        val rows: List<List<HamsterKey>>,
    )

    /** 读取 [dir] 下的元书皮肤；结构不对时返回 null。 */
    fun parse(dir: File, dark: Boolean = false): HamsterRows? {
        val config = runCatching { readConfig(File(dir, "config.yaml")) }.getOrNull()
        val modeDir = File(dir, if (dark) "dark" else "light")
        if (!modeDir.isDirectory) {
            Timber.w("hamster: %s 缺少 %s 目录", dir.name, modeDir.name)
            return null
        }

        val candidates = buildList {
            config?.let { add(it) }
            add("pinyinPortrait")
            add("pinyin_26_portrait")
        }
        val file = candidates
            .asSequence()
            .map { File(modeDir, "$it.yaml") }
            .firstOrNull { it.isFile }
            ?: modeDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }
                ?.sortedBy { it.name }
                ?.firstOrNull()
            ?: return null

        val root = runCatching {
            json.parseToJsonElement(file.readText()).jsonObject
        }.getOrElse {
            Timber.w(it, "hamster: 解析 %s 失败", file.name)
            return null
        }
        return runCatching { buildRows(root, file.nameWithoutExtension) }
            .onFailure { Timber.w(it, "hamster: 构造按键失败 %s", file.name) }
            .getOrNull()
    }

    /**
     * config.yaml 只需取出 `pinyin.phone.portrait`（或 iPhone.portrait）。
     * 文件很小，逐行读缩进足够，避免为它引入完整 YAML 解析。
     */
    private fun readConfig(file: File): String? {
        if (!file.isFile) return null
        var section = ""
        var device = ""
        for (raw in file.readLines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (indent == 0) {
                section = line.substringBefore(':').trim()
                device = ""
                continue
            }
            if (indent <= 2) {
                device = line.substringBefore(':').trim()
                continue
            }
            if (section == "pinyin" && (device == "phone" || device == "iPhone")) {
                val key = line.substringBefore(':').trim()
                if (key == "portrait") {
                    return line.substringAfter(':').trim().trim('"', '\'')
                }
            }
        }
        return null
    }

    private fun buildRows(root: JsonObject, name: String): HamsterRows? {
        val layout = root["keyboardLayout"] as? JsonArray ?: return null
        val rows = layout.mapNotNull { row ->
            val stack = (row as? JsonObject)?.get("HStack") as? JsonObject ?: return@mapNotNull null
            val cells = stack["subviews"] as? JsonArray ?: return@mapNotNull null
            resolveRow(root, cells)
        }
        return HamsterRows(name, rows)
    }

    private fun resolveRow(root: JsonObject, cells: JsonArray): List<HamsterKey> {
        val names = cells.mapNotNull { cell ->
            (cell as? JsonObject)?.values?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }
        }
        val defs = names.map { name -> name to (root[name] as? JsonObject) }
        val fixed = defs.sumOf { (_, def) -> def?.widthPercent()?.toDouble() ?: 0.0 }
        val flexible = defs.count { (_, def) -> def?.widthPercent() == null }
        val share = if (flexible > 0) ((1.0 - fixed).coerceAtLeast(0.01) / flexible).toFloat() else 0f

        return defs.map { (_, def) ->
            if (def == null) {
                HamsterKey(widthPercent = share * 100f)
            } else {
                resolveKey(root, def, share)
            }
        }
    }

    private fun JsonObject.widthPercent(): Float? =
        ((this["size"] as? JsonObject)?.get("width") as? JsonObject)
            ?.get("percentage")
            ?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }
            ?.toFloatOrNull()

    private fun resolveKey(root: JsonObject, def: JsonObject, fallbackShare: Float): HamsterKey {
        val action = def["action"]
        val click = actionToken(action)
        val label = foregroundText(root, def["foregroundStyle"])
            ?.takeUnless { it.startsWith("$") }
            ?: actionCharacter(action)
            ?: fallbackLabel(actionToken(action))

        val fg = style(root, def["foregroundStyle"])
        val bg = style(root, def["backgroundStyle"])

        val width = (def.widthPercent() ?: fallbackShare) * 100f

        return HamsterKey(
            label = label,
            widthPercent = width,
            click = click,
            swipeUp = actionToken(def["swipeUpAction"]),
            swipeDown = actionToken(def["swipeDownAction"]),
            textColor = fg?.get("normalColor")?.asString(),
            textSize = fg?.get("fontSize")?.asFloat() ?: 0f,
            backColor = bg?.let { if (it["buttonStyleType"]?.asString() == "geometry") it["normalColor"]?.asString() else null },
            backColorPressed = bg?.let { if (it["buttonStyleType"]?.asString() == "geometry") it["highlightColor"]?.asString() else null },
            cornerRadius = bg?.get("cornerRadius")?.asFloat() ?: -1f,
        )
    }

    /** 样式名可能是字符串，也可能是「降级链」数组；取第一个能查到的。 */
    private fun style(root: JsonObject, ref: JsonElement?): JsonObject? {
        val names = when (ref) {
            is JsonPrimitive -> listOf(ref.contentOrNullSafe())
            is JsonArray -> ref.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            else -> return null
        }
        return names.firstNotNullOfOrNull { name -> root[name] as? JsonObject }
    }

    private fun foregroundText(root: JsonObject, ref: JsonElement?): String? {
        val fg = style(root, ref) ?: return null
        if (fg["buttonStyleType"]?.asString() != "text") return null
        return fg["text"]?.asString()
    }

    /** 元书的功能键用图片/图标，转成同文后先用文字顶上。 */
    private fun fallbackLabel(token: String?): String = when (token) {
        "Shift" -> "⇧"
        "BackSpace" -> "⌫"
        "space" -> "空格"
        "Return" -> "回车"
        "Tab" -> "Tab"
        "Keyboard_numeric" -> "123"
        "Keyboard_symbolic" -> "符号"
        "Keyboard_default" -> "英文"
        else -> ""
    }

    private fun actionCharacter(action: JsonElement?): String? =
        (action as? JsonObject)?.get("character")?.asString()

    /** 元书动作 → 同文的动作 token。 */
    private fun actionToken(action: JsonElement?): String? = when (action) {
        null -> null
        is JsonPrimitive -> when (val raw = action.contentOrNullSafe()) {
            "enter" -> "Return"
            "space" -> "space"
            "backspace" -> "BackSpace"
            "shift" -> "Shift"
            "tab" -> "Tab"
            "escape" -> "Escape"
            else -> raw?.takeIf { it.isNotBlank() }
        }

        is JsonObject -> {
            action["character"]?.asString()
                ?: action["symbol"]?.asString()
                ?: when (action["keyboardType"]?.asString()) {
                    "numeric" -> "Keyboard_numeric"
                    "symbolic" -> "Keyboard_symbolic"
                    "alphabetic" -> "Keyboard_default"
                    else -> null
                }
        }

        else -> null
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() }

    private fun JsonElement?.asFloat(): Float? = asString()?.toFloatOrNull()

    private fun JsonPrimitive.contentOrNullSafe(): String? = content.takeIf { it.isNotBlank() }
}

/** 把解析出的行落成同文可用的 [Theme]。 */
object HamsterThemeBuilder {
    fun build(skinName: String, parsed: HamsterSkinParser.HamsterRows, dark: Boolean): Theme {
        val keys = parsed.rows.flatten().map { key ->
            TextKeyboard.TextKey(
                width = key.widthPercent,
                label = key.label,
                keyTextSize = key.textSize,
                roundCorner = key.cornerRadius,
                keyTextColor = key.textColor?.toTrimeColor().orEmpty(),
                keyBackColor = key.backColor?.toTrimeColor().orEmpty(),
                hilitedKeyBackColor = key.backColorPressed?.toTrimeColor().orEmpty(),
                click = key.click?.let(KeyActionToken::Plain),
                swipeUp = key.swipeUp?.let(KeyActionToken::Plain),
                swipeDown = key.swipeDown?.let(KeyActionToken::Plain),
            )
        }

        val keyboard = TextKeyboard(
            name = skinName,
            width = 100f,
            height = parsed.rows.size.toFloat(),
            columns = -1,
            keys = keys,
        )

        val scheme = if (dark) {
            mapOf("back_color" to "0xFF1C1C1E".toULongColor(), "text_color" to "0xFFFFFFFF".toULongColor())
        } else {
            mapOf("back_color" to "0xFFD1D3D9".toULongColor(), "text_color" to "0xFF000000".toULongColor())
        }

        return Theme(
            name = skinName,
            style = GeneralStyle(),
            presetKeyboards = mapOf("default" to keyboard),
            presetColorSchemes = mapOf("default" to scheme),
        )
    }

    private fun String.toTrimeColor(): String = toULongColor()

    /** `#RRGGBB` / `#RRGGBBAA` → `0xAARRGGBB`，其它原样返回让上层按名字解析。 */
    private fun String.toULongColor(): String {
        val raw = trim()
        if (!raw.startsWith("#")) return raw
        val hex = raw.removePrefix("#")
        return when (hex.length) {
            6 -> "0xFF${hex.uppercase()}"
            8 -> "0x${hex.substring(6, 8)}${hex.substring(0, 6)}".uppercase().replace("0X", "0x")
            else -> raw
        }
    }
}
