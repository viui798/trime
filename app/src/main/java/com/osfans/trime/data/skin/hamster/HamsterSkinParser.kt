/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.skin.hamster

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey
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
    private val KINDS = setOf("pinyin", "numeric", "symbolic")
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
        /** cskin 的 fileImage 背景：精灵图文件名 + 切片名，由 [HamsterSlices] 切成九宫格。 */
        val backImage: ImageRef? = null,
        val backImagePressed: ImageRef? = null,
        /** cskin 的 fileImage 前景（字母本身是图片），画在键帽上而不是画文字。 */
        val foregroundImage: ImageRef? = null,
        /** 键位左上角的小字（元书的上滑提示，如 q 键的 `1`）。 */
        val symbol: String = "",
        /** 键位右上角的小字（元书的下滑提示，如 q 键的 `!`）。 */
        val symbolRight: String = "",
        /** 键位下方的长按命令（如 z 键的「撤销」）。 */
        val hint: String = "",
    )

    /** 元书精灵图里的一张切片引用。 */
    data class ImageRef(
        val file: String,
        val image: String,
    )

    /** 一个带位置的按键。[x]/[y]/[w]/[h] 是相对键盘的 0..1 值。 */
    data class PlacedKey(
        val key: HamsterKey,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    data class HamsterRows(
        val name: String,
        /** 流式（行）表示，回落键盘与解析不出布局树时使用。 */
        val rows: List<List<HamsterKey>> = emptyList(),
        /** 布局树算出的显式矩形；非空时优先于 [rows]。 */
        val cells: List<PlacedKey> = emptyList(),
    )

    /** 一套皮肤：[default] 主键盘 + 可选的 [numeric] / [symbolic]。 */
    data class HamsterSkin(
        val name: String,
        val dir: File,
        val keyboards: Map<String, HamsterRows>,
    )

    /** 读取 [dir] 下的元书皮肤；一套键盘都读不出来时返回 null。 */
    fun parse(dir: File, dark: Boolean = false): HamsterSkin? {
        val modeDir = File(dir, if (dark) "dark" else "light")
        if (!modeDir.isDirectory) {
            Timber.w("hamster: %s 缺少 %s 目录", dir.name, modeDir.name)
            return null
        }
        val declared = runCatching { readConfig(File(dir, "config.yaml")) }.getOrDefault(emptyMap())

        // cskin 的 config 只声明它自己有的键盘，缺的一律按常见文件名回落。
        val specs = listOf(
            "default" to listOfNotNull(declared["pinyin"], "pinyinPortrait", "pinyin_26_portrait"),
            "numeric" to listOfNotNull(declared["numeric"], "numeric_9_portrait", "numeric_9"),
            "symbolic" to listOfNotNull(declared["symbolic"], "symbolic_portrait", "symbolic"),
        )

        val roots = mutableMapOf<String, JsonObject>()
        val keyboards = specs.mapNotNull { (id, names) ->
            val file = names
                .asSequence()
                .map { File(modeDir, "$it.yaml") }
                .firstOrNull { it.isFile }
                ?: return@mapNotNull null
            val root = runCatching {
                json.parseToJsonElement(file.readText()).jsonObject
            }.getOrElse {
                Timber.w(it, "hamster: 解析 %s 失败", file.name)
                return@mapNotNull null
            }
            roots[id] = root
            val rows = runCatching { buildKeyboard(root, file.nameWithoutExtension) }
                .onFailure { Timber.w(it, "hamster: 构造按键失败 %s", file.name) }
                .getOrNull()
                ?: return@mapNotNull null
            id to rows
        }.toMap()

        if (keyboards.isEmpty()) return null

        // 数字键盘的符号条可能有多屏，其余屏各生成一个键盘（滑动切换）。
        val numericPages = mutableMapOf<String, HamsterRows>()
        roots["numeric"]?.let { numericRoot ->
            val total = parseSymbolList(numericRoot["symbols"]).size
            val screens = ((total + 3) / 4).coerceAtLeast(1)
            for (screen in 1 until screens) {
                buildKeyboard(numericRoot, "numeric_s$screen", symbolPage = screen)
                    ?.let { numericPages["numeric_s$screen"] = it }
            }
        }

        // 解析出 0 个键的键盘视为不可用：元书的数字 / 符号键盘是 VStack 列式布局，
        // 目前只支持 HStack 行式，解析不出来时先回落到生成的版本，避免切过去是空白。
        val valid = keyboards.filterValues { rows ->
            rows.cells.isNotEmpty() || rows.rows.any { it.isNotEmpty() }
        }

        // 回落键盘沿用主键盘的键帽（图片皮肤要一致，纯色会和主键盘撞车）。
        val mainRows = valid["default"]
        val mainKeys = mainRows?.cells?.map { it.key }
            ?: mainRows?.rows?.flatten().orEmpty()
        val alphaCap = mainKeys.firstOrNull { it.backImage != null && it.label.length == 1 }?.backImage
        val enterCap = mainKeys.firstOrNull { it.click?.contains("Return") == true }?.backImage
            ?: alphaCap

        // 符号页：按 cskin 的 category 列表与各类符号展开成「一个分类的一页一个键盘」。
        val symbolicPages = buildSymbolicPages(
            root = roots["symbolic"],
            keyCap = alphaCap,
            enterCap = enterCap,
        )

        // 先补符号键盘，数字键盘底行的 #+= 才知道能不能切过去。
        val withSymbolic = when {
            symbolicPages.isNotEmpty() ->
                valid - "symbolic" + symbolicPages + mapOf(
                    "symbolic" to symbolicPages.values.first(),
                )

            "symbolic" in valid -> valid
            else -> valid + ("symbolic" to defaultSymbolicRows(dark, alphaCap))
        }
        val withNumeric = if ("numeric" in withSymbolic) {
            withSymbolic
        } else {
            withSymbolic + ("numeric" to defaultNumericRows(dark, hasSymbolic = true, alphaCap, enterCap))
        }
        return HamsterSkin(dir.name, dir, withNumeric + numericPages)
    }

    /**
     * config.yaml 只需取出 pinyin / numeric / symbolic 的竖屏文件名。
     * 文件很小，逐行读缩进足够，避免为它引入完整 YAML 解析。
     */
    private fun readConfig(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!file.isFile) return result
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
            if (section in KINDS && (device == "phone" || device == "iPhone")) {
                val key = line.substringBefore(':').trim()
                if (key == "portrait") {
                    result[section] = line.substringAfter(':').trim().trim('"', '\'')
                }
            }
        }
        return result
    }

    /**
     * 读 cskin 的 `symbols` 数组。
     *
     * 条目有两种写法：`{"action": {"character": "+"}, "label": "+"}` 和裸字符串 `"()"`。
     * 返回 `(显示文字, 要输入的字符)`。
     */
    private fun parseSymbolList(element: JsonElement?): List<Pair<String, String>> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNullSafe()?.let { it to it }
                is JsonObject -> {
                    val click = actionCharacter(item["action"])
                    val label = item["label"]?.asString() ?: click
                    if (label.isNullOrBlank() || click.isNullOrBlank()) null else label to click
                }

                else -> null
            }
        }
    }

    /** 把 cskin 的 category / 各类符号展开成一组符号页；数据缺失时返回空。 */
    private fun buildSymbolicPages(
        root: JsonObject?,
        keyCap: ImageRef?,
        enterCap: ImageRef?,
    ): Map<String, HamsterRows> {
        if (root == null) return emptyMap()
        val categories = (root["category"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            ?.filter { it.isNotBlank() }
            ?: return emptyMap()
        if (categories.isEmpty()) return emptyMap()

        val symbolsByCategory = categories.associateWith { category ->
            (root[category] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }

        val pageSize = 25
        val result = mutableMapOf<String, HamsterRows>()
        categories.forEachIndexed { index, _ ->
            val count = symbolsByCategory[categories[index]].orEmpty().size
            val pages = ((count + pageSize - 1) / pageSize).coerceAtLeast(1)
            for (page in 0 until pages) {
                val id = "symbolic_${index}_$page"
                result[id] = buildSymbolicPage(categories, symbolsByCategory, index, page, keyCap, enterCap)
            }
        }
        return result
    }

    /**
     * 生成一页符号键盘（按 cskin 的 symbolic 定义）。
     *
     * 版面取自 skin 的 keyboardLayout：
     * - 上方 227/281：左侧分类列（56/375 宽）+ 右侧符号网格（319/375 宽，5 列 × 5 行）
     * - 下方 54/281：返回 / 上翻 / 下翻 / 锁定 / 退格
     *
     * 分类有 21 个、符号 1600 多个，全部展开会得到几十个键盘；
     * 这里「一个分类的一页」生成一个键盘，靠 preset_keys 互相切换。
     */
    private fun buildSymbolicPage(
        categories: List<String>,
        symbolsByCategory: Map<String, List<String>>,
        catIndex: Int,
        page: Int,
        keyCap: ImageRef?,
        enterCap: ImageRef?,
    ): HamsterRows {
        val topHeight = 227f / 281f
        val bottomHeight = 1f - topHeight
        val categoryWidth = 56f / 375f
        val gridX = categoryWidth
        val gridW = 1f - categoryWidth
        val columns = 5
        val rows = 5
        val pageSize = columns * rows

        val cells = mutableListOf<PlacedKey>()

        // 左侧分类列：以当前分类为中心取 5 个，点击切到该分类的第一页。
        val windowSize = 5
        val start = (catIndex - 2).coerceIn(0, (categories.size - windowSize).coerceAtLeast(0))
        val slotHeight = topHeight / windowSize
        for (i in 0 until windowSize) {
            val index = start + i
            val name = categories.getOrNull(index) ?: continue
            val active = index == catIndex
            cells += PlacedKey(
                key = HamsterKey(
                    label = name,
                    click = "Hamster_sym_${index}_0",
                    textColor = if (active) "0xFF2E2E2E" else "0xFF8A8A8A",
                    backImage = keyCap,
                ),
                x = 0f,
                y = slotHeight * i,
                w = categoryWidth,
                h = slotHeight,
            )
        }

        // 右侧符号网格。
        val symbols = symbolsByCategory[categories[catIndex]].orEmpty()
        val pageCount = ((symbols.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val safePage = page.coerceIn(0, pageCount - 1)
        val cellW = gridW / columns
        val cellH = topHeight / rows
        symbols.drop(safePage * pageSize).take(pageSize).forEachIndexed { i, symbol ->
            val col = i % columns
            val row = i / columns
            cells += PlacedKey(
                key = HamsterKey(
                    label = symbol,
                    click = symbol,
                    backImage = keyCap,
                ),
                x = gridX + cellW * col,
                y = cellH * row,
                w = cellW,
                h = cellH,
            )
        }

        // 底行五个按钮。
        val bottomKeys = listOf(
            HamsterKey(label = "返回", click = "Hamster_alpha", backImage = keyCap),
            HamsterKey(
                label = "▲",
                click = if (safePage > 0) "Hamster_sym_${catIndex}_${safePage - 1}" else "Hamster_sym_${catIndex}_0",
                backImage = keyCap,
            ),
            HamsterKey(
                label = "▼",
                click = if (safePage + 1 < pageCount) {
                    "Hamster_sym_${catIndex}_${safePage + 1}"
                } else {
                    "Hamster_sym_${catIndex}_${pageCount - 1}"
                },
                backImage = keyCap,
            ),
            HamsterKey(label = "锁", click = null, textColor = "0xFF8A8A8A", backImage = keyCap),
            HamsterKey(label = "⌫", click = "BackSpace", backImage = enterCap ?: keyCap),
        )
        val buttonW = 1f / bottomKeys.size
        bottomKeys.forEachIndexed { i, key ->
            cells += PlacedKey(
                key = key.copy(widthPercent = buttonW * 100f),
                x = buttonW * i,
                y = topHeight,
                w = buttonW,
                h = bottomHeight,
            )
        }

        return HamsterRows("symbolic_${catIndex}_$safePage", cells = cells)
    }

    /**
     * 元书内置的符号键盘（cskin 里同样没有定义）。
     *
     * 8 列 × 4 行：三行常用中英文标点，底行返回 / 空格 / 退格。
     */
    private fun defaultSymbolicRows(dark: Boolean, keyCap: ImageRef?): HamsterRows {
        val keyBack = if (dark) "0xFF2C2C2E" else "0xFFFFFFFF"
        val fnBack = if (dark) "0xFF1F1F21" else "0xFFEDEDEF"
        val textColor = if (dark) "0xFFFFFFFF" else "0xFF000000"
        val corner = 8.5f
        val cell = 12.5f

        fun sym(text: String) = HamsterKey(
            label = text,
            widthPercent = cell,
            click = text,
            backColor = keyBack,
            textColor = textColor,
            cornerRadius = corner,
            backImage = keyCap,
        )

        val rows = listOf(
            listOf("，", "。", "？", "！", "：", "；", "、", "…").map(::sym),
            listOf("（", "）", "《", "》", "「", "」", "·", "—").map(::sym),
            listOf("@", "#", "￥", "%", "&", "*", "+", "=").map(::sym),
            listOf(
                HamsterKey(label = "返回", widthPercent = 25f, click = "Hamster_alpha", backColor = fnBack, textColor = textColor, cornerRadius = corner),
                HamsterKey(label = "空格", widthPercent = 50f, click = "space", backColor = fnBack, textColor = textColor, cornerRadius = corner),
                HamsterKey(label = "⌫", widthPercent = 25f, click = "BackSpace", backColor = fnBack, textColor = textColor, cornerRadius = corner),
            ),
        )
        return HamsterRows("symbolic", rows)
    }

    /**
     * 元书内置的数字键盘。
     *
     * cskin 的 config 里没有 numeric 条目（元书那套是应用自带的），所以按它的实际
     * 样子生成：5 列 × 4 行——左列 @ % - +，中间 1-9，右列 ⌫ . =，
     * 底行 返回 / 0 / 空格 / 发送（发送为蓝色高亮）。
     *
     * 列宽取自元书 numeric 布局（VStackStyle 的 29/183 与 125/549）。
     * 同文是流式行布局，表达不了「左列 4 个键挤在 3 行高度里」，
     * 所以 + 落在第四行最左，与底行同排。
     */
    private fun defaultNumericRows(
        dark: Boolean,
        hasSymbolic: Boolean,
        keyCap: ImageRef?,
        enterCap: ImageRef?,
    ): HamsterRows {
        val digitBack = if (dark) "0xFF2C2C2E" else "0xFFFFFFFF"
        val fnBack = if (dark) "0xFF1F1F21" else "0xFFEDEDEF"
        val textColor = if (dark) "0xFFFFFFFF" else "0xFF000000"
        val accentBack = if (dark) "0xFF0A84FF" else "0xFF4C8DF6"
        val corner = 8.5f

        val left = 15.85f
        val mid = 22.77f
        val right = 15.85f

        fun digit(text: String) = HamsterKey(
            label = text,
            widthPercent = mid,
            click = text,
            backColor = digitBack,
            textColor = textColor,
            cornerRadius = corner,
            backImage = keyCap,
        )

        fun symbol(text: String) = HamsterKey(
            label = text,
            widthPercent = left,
            click = text,
            backColor = fnBack,
            textColor = textColor,
            cornerRadius = corner,
            backImage = keyCap,
        )

        fun fn(label: String, action: String?) = HamsterKey(
            label = label,
            widthPercent = right,
            click = action,
            backColor = fnBack,
            textColor = textColor,
            cornerRadius = corner,
            backImage = keyCap,
        )

        val top = listOf(
            listOf(symbol("@"), digit("1"), digit("2"), digit("3"), fn("⌫", "BackSpace")),
            listOf(symbol("%"), digit("4"), digit("5"), digit("6"), fn(".", ".")),
            listOf(symbol("-"), digit("7"), digit("8"), digit("9"), fn("=", "=")),
        )

        // 底行：+ / (符号页) / 返回 / 0 / 空格 / 发送
        val bottom = mutableListOf(
            HamsterKey(
                label = "+",
                widthPercent = left,
                click = "+",
                backColor = fnBack,
                textColor = textColor,
                cornerRadius = corner,
                backImage = keyCap,
            ),
        )
        val rest = 100f - left
        val slots = mutableListOf<HamsterKey>()
        if (hasSymbolic) {
            slots += HamsterKey(label = "#+=", click = "Hamster_symbolic", backColor = fnBack, textColor = textColor, cornerRadius = corner, backImage = keyCap)
        }
        slots += HamsterKey(label = "返回", click = "Hamster_alpha", backColor = fnBack, textColor = textColor, cornerRadius = corner, backImage = keyCap)
        slots += HamsterKey(label = "0", click = "0", backColor = digitBack, textColor = textColor, cornerRadius = corner, backImage = keyCap)
        slots += HamsterKey(label = "空格", click = "space", backColor = fnBack, textColor = textColor, cornerRadius = corner, backImage = keyCap)
        slots += HamsterKey(
            label = "发送",
            click = "Return",
            backColor = accentBack,
            textColor = "0xFFFFFFFF",
            cornerRadius = corner,
            backImage = enterCap ?: keyCap,
        )
        val slotWidth = rest / slots.size
        bottom += slots.map { it.copy(widthPercent = slotWidth) }

        return HamsterRows("numeric", top + listOf(bottom))
    }

    /**
     * 优先按布局树求值（支持 HStack/VStack、分数尺寸、跨行），
     * 求不出来时退回旧的「一行一个 HStack」解析。
     */
    private fun buildKeyboard(
        root: JsonObject,
        name: String,
        symbolPage: Int = 0,
    ): HamsterRows? {
        val layoutCells = runCatching { HamsterLayout.layout(root) }.getOrDefault(emptyList())
        if (layoutCells.isNotEmpty()) {
            // 数字键盘左列是可滑动的符号条：cskin 的 `symbols` 数组给出符号表，
            // 单元格里一屏放 4 个，上下滑切换下一页。
            val symbolList = parseSymbolList(root["symbols"])
            val perScreen = 4
            val screenCount = ((symbolList.size + perScreen - 1) / perScreen).coerceAtLeast(1)
            val screen = symbolPage.coerceIn(0, screenCount - 1)

            val placed = layoutCells.flatMap { cell ->
                if (cell.name == "collection") {
                    val items = if (symbolList.isNotEmpty()) {
                        symbolList.drop(screen * perScreen).take(perScreen)
                    } else {
                        listOf("@", "%", "-", "+").map { it to it }
                    }
                    val step = cell.height / items.size
                    items.mapIndexed { index, item ->
                        PlacedKey(
                            key = HamsterKey(
                                label = item.first,
                                click = item.second,
                                swipeUp = if (screen + 1 < screenCount) "Hamster_num_s${screen + 1}" else null,
                                swipeDown = if (screen > 0) "Hamster_num_s${screen - 1}" else null,
                            ),
                            x = cell.x,
                            y = cell.y + step * index,
                            w = cell.width,
                            h = step,
                        )
                    }
                } else {
                    val def = root[cell.name] as? JsonObject ?: return@flatMap emptyList()
                    listOf(
                        PlacedKey(
                            key = resolveKey(root, def, 0f, cell.name),
                            x = cell.x,
                            y = cell.y,
                            w = cell.width,
                            h = cell.height,
                        ),
                    )
                }
            }
            if (placed.isNotEmpty()) return HamsterRows(name, cells = placed)
        }
        return buildRows(root, name)
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

        return defs.map { (name, def) ->
            if (def == null) {
                HamsterKey(widthPercent = share * 100f)
            } else {
                resolveKey(root, def, share, name)
            }
        }
    }

    private fun JsonObject.widthPercent(): Float? =
        ((this["size"] as? JsonObject)?.get("width") as? JsonObject)
            ?.get("percentage")
            ?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }
            ?.toFloatOrNull()

    private fun resolveKey(
        root: JsonObject,
        def: JsonObject,
        fallbackShare: Float,
        buttonName: String?,
    ): HamsterKey {
        val action = def["action"]
        val click = actionToken(action)
        val label = foregroundText(root, def["foregroundStyle"])
            ?.takeUnless { it.startsWith("$") }
            ?: actionCharacter(action)
            ?: fallbackLabel(actionToken(action)).ifEmpty { namedLabel(buttonName) }

        val fgChain = styles(root, def["foregroundStyle"])
        val fg = fgChain.firstOrNull()
        // 元书把「上滑 / 下滑提示」也放在 foregroundStyle 的数组里，例如 q 键是
        // [字母图片, "1", "!"]，显示在键帽左上角。
        // 元书的角标不是单独的样式，而是「上滑 / 下滑动作」的提示：
        // q 键 swipeUp=1、swipeDown=!；z 键 swipeDown={shortcut: "#undo"} → 「撤销」。
        // 字符类画在左上 / 右上，命令类（shortcut）画在键位下方。
        val upLabel = actionLabel(def["swipeUpAction"])
        val downLabel = actionLabel(def["swipeDownAction"])
        val symbol = upLabel?.takeIf { !it.second }?.first.orEmpty()
        val symbolRight = downLabel?.takeIf { !it.second }?.first.orEmpty()
        val hint = (downLabel?.takeIf { it.second } ?: upLabel?.takeIf { it.second })
            ?.first
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val bg = style(root, def["backgroundStyle"])
        val isFileImage = bg?.get("buttonStyleType")?.asString() == "fileImage"
        val isFgImage = fg?.get("buttonStyleType")?.asString() == "fileImage"

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
            symbol = symbol,
            symbolRight = symbolRight,
            hint = hint,
            foregroundImage = if (isFgImage) fg?.get("normalImage").toImageRef() else null,
            backImage = if (isFileImage) bg?.get("normalImage").toImageRef() else null,
            backImagePressed = if (isFileImage) bg?.get("highlightImage").toImageRef() else null,
        )
    }

    /** `{"file": "26anjian", "image": "IMG1"}` → [ImageRef]。 */
    private fun JsonElement?.toImageRef(): ImageRef? {
        val obj = this as? JsonObject ?: return null
        val file = obj["file"]?.asString() ?: return null
        val image = obj["image"]?.asString() ?: return null
        return ImageRef(file, image)
    }

    /**
     * 把上滑 / 下滑动作转成显示文字。
     *
     * 返回 `(文字, 是否命令)`：命令类（shortcut）显示在键位下方，字符类显示在角上。
     */
    private fun actionLabel(action: JsonElement?): Pair<String, Boolean>? = when (action) {
        null -> null
        is JsonPrimitive -> {
            val raw = action.contentOrNullSafe() ?: return null
            val text = when (raw) {
                "tab" -> "⇤"
                "shift" -> "⇧"
                "backspace" -> "⌫"
                "enter", "return" -> "⏎"
                "space" -> "空格"
                else -> raw.takeIf { it.isNotBlank() }
            } ?: return null
            text to false
        }

        is JsonObject -> {
            action["character"]?.asString()?.let { return it to false }
            action["symbol"]?.asString()?.let { return it to false }
            action["shortcut"]?.asString()?.let { return shortcutLabel(it) to true }
            null
        }

        else -> null
    }

    /** 元书的 shortcut 用 `#` 前缀，显示成用户看得懂的中文。 */
    private fun shortcutLabel(shortcut: String): String = when (shortcut) {
        "#undo" -> "撤销"
        "#redo" -> "重做"
        "#cut" -> "剪切"
        "#copy" -> "复制"
        "#paste" -> "粘贴"
        "#selectAll" -> "全选"
        "#selectall" -> "全选"
        "#textEditing" -> "文本编辑"
        "#moveCursorLeft" -> "左移"
        "#moveCursorRight" -> "右移"
        // 未知命令宁可不显示，也不要塞一个同文自造的词。
        else -> ""
    }

    /** 降级链上的所有样式（有序），用于同时读取主前景与角标。 */
    private fun styles(root: JsonObject, ref: JsonElement?): List<JsonObject> {
        val names = when (ref) {
            is JsonPrimitive -> listOf(ref.contentOrNullSafe())
            is JsonArray -> ref.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            else -> return emptyList()
        }
        return names.mapNotNull { name -> root[name] as? JsonObject }
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

    /** 少数键在 cskin 里只有名字（英文切换等），按名字给个可读标签。 */
    private fun namedLabel(buttonName: String?): String = when {
        buttonName == null -> ""
        "ascii" in buttonName.lowercase() -> "英"
        "emoji" in buttonName.lowercase() -> "☺"
        else -> ""
    }

    private fun actionCharacter(action: JsonElement?): String? =
        (action as? JsonObject)?.get("character")?.asString()

    /** 元书动作 → 同文的动作 token。 */
    private fun actionToken(action: JsonElement?): String? = when (action) {
        null -> null
        is JsonPrimitive -> when (val raw = action.contentOrNullSafe()) {
            "enter", "return" -> "Return"
            "delete" -> "BackSpace"
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
                    "alphabetic", "pinyin" -> "Keyboard_default"
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
    private const val KEYBOARD_HEIGHT_DP = 268
    private const val KEYBOARD_HEIGHT_LAND_DP = 200
    private const val DEFAULT_TEXT_SIZE = 22.5f
    private const val DEFAULT_LONG_TEXT_SIZE = 16f
    private const val DEFAULT_SYMBOL_TEXT_SIZE = 12f

    fun build(skinName: String, skin: HamsterSkinParser.HamsterSkin, dark: Boolean): Theme {
        val available = skin.keyboards.keys
        val keyboards = skin.keyboards.mapValues { (id, rows) -> toKeyboard(skin, id, rows, available, dark) }

        // 兜底配色：cskin 里没有显式文字色的键（图标键）也要能看见。
        val scheme = if (dark) {
            mapOf(
                "back_color" to "0xFF1C1C1E".toULongColor(),
                "text_color" to "0xFFFFFFFF".toULongColor(),
                "key_back_color" to "0xFF2C2C2E".toULongColor(),
                "key_text_color" to "0xFFFFFFFF".toULongColor(),
                "key_symbol_color" to "0xFFB0B0B5".toULongColor(),
                "hilited_key_back_color" to "0xFF3A3A3C".toULongColor(),
                "hilited_key_text_color" to "0xFFFFFFFF".toULongColor(),
                "off_key_back_color" to "0xFF2C2C2E".toULongColor(),
                "off_key_text_color" to "0xFFFFFFFF".toULongColor(),
                "on_key_back_color" to "0xFF3A3A3C".toULongColor(),
                "on_key_text_color" to "0xFFFFFFFF".toULongColor(),
            )
        } else {
            mapOf(
                "back_color" to "0xFFD1D3D9".toULongColor(),
                "text_color" to "0xFF000000".toULongColor(),
                "key_back_color" to "0xFFFFFFFF".toULongColor(),
                "key_text_color" to "0xFF000000".toULongColor(),
                "key_symbol_color" to "0xFF6B6B70".toULongColor(),
                "hilited_key_back_color" to "0xFFE6E6E6".toULongColor(),
                "hilited_key_text_color" to "0xFF000000".toULongColor(),
                "off_key_back_color" to "0xFFFFFFFF".toULongColor(),
                "off_key_text_color" to "0xFF000000".toULongColor(),
                "on_key_back_color" to "0xFFE6E6E6".toULongColor(),
                "on_key_text_color" to "0xFF000000".toULongColor(),
            )
        }

        return Theme(
            name = skinName,
            // 底座样式：键盘高度必须给值，否则按键区高度为 0（只剩工具栏一条）。
            style = GeneralStyle(
                keyboardHeight = KEYBOARD_HEIGHT_DP,
                keyboardHeightLand = KEYBOARD_HEIGHT_LAND_DP,
                keyTextSize = DEFAULT_TEXT_SIZE,
                keyLongTextSize = DEFAULT_LONG_TEXT_SIZE,
                symbolTextSize = DEFAULT_SYMBOL_TEXT_SIZE,
                horizontalGap = 4,
                verticalGap = 4,
                keyBorder = 0,
            ),
            presetKeyboards = keyboards,
            // cskin 的「切键盘」动作落到同文的 Eisu 上；皮肤没有的键盘不生成入口。
            presetKeys = buildMap {
                if ("numeric" in available) {
                    put("Hamster_numeric", PresetKey(send = "Eisu_toggle", select = "numeric"))
                }
                if ("symbolic" in available) {
                    put("Hamster_symbolic", PresetKey(send = "Eisu_toggle", select = "symbolic"))
                }
                put("Hamster_alpha", PresetKey(send = "Eisu_toggle", select = ".default"))
                // 符号页：分类切换与翻页都靠这些预设键（symbolic_<分类>_<页>）。
                available.filter { it.startsWith("symbolic_") }.forEach { id ->
                    put("Hamster_sym_" + id.removePrefix("symbolic_"), PresetKey(send = "Eisu_toggle", select = id))
                }
                // 数字键盘符号条的翻页。
                available.filter { it.startsWith("numeric_s") }.forEach { id ->
                    put("Hamster_num_" + id.removePrefix("numeric_"), PresetKey(send = "Eisu_toggle", select = id))
                }
            },
            presetColorSchemes = mapOf("default" to scheme),
        )
    }

    private fun toKeyboard(
        skin: HamsterSkinParser.HamsterSkin,
        id: String,
        rows: HamsterSkinParser.HamsterRows,
        available: Set<String>,
        dark: Boolean,
    ): TextKeyboard {
        // 皮肤里用图标、没写文字色的键（英文切换等）用一个可见的缺省色兜住。
        val fallbackText = if (dark) "0xFFFFFFFF" else "0xFF000000"

        // 布局树路径：每个键自带矩形，直接交给 Keyboard 的显式布局。
        if (rows.cells.isNotEmpty()) {
            val placedKeys = rows.cells.mapNotNull { placed ->
                toTextKey(skin, dark, placed.key, placed, available, fallbackText)
            }
            Timber.i(
                "hamster: keyboard %s cells=%d keys=%s",
                id,
                placedKeys.size,
                placedKeys.joinToString(" ") { "${it.label.ifBlank { "·" }}@${"%.2f".format(it.xPercent)}" },
            )
            return TextKeyboard(
                name = skin.name,
                width = 100f,
                height = 100f,
                columns = -1,
                keys = placedKeys,
            )
        }

        val keys = rows.rows.flatMap { row ->
            val out = mutableListOf<TextKeyboard.TextKey>()
            row.forEach { key ->
                val token = key.click?.let { translateAction(it, available) }
                val unsupported = key.click == null ||
                    (key.click.startsWith("Keyboard_") && token == null)
                if (unsupported) {
                    // 没有可执行动作（元书私有 shortcut 等）：并进前一个键，
                    // 否则会留下一个「看不见、按了也没反应」的空白键。
                    val prev = out.lastOrNull()
                    if (prev != null) {
                        out[out.lastIndex] = prev.copy(width = prev.width + key.widthPercent)
                    }
                    return@forEach
                }
                // 元书的键帽是精灵图切片；切不出来（缺资源）才退回纯色。
                val backImage = key.backImage?.let { HamsterSlices.slice(skin.dir, dark, it.file, it.image) }
                val backImagePressed = key.backImagePressed?.let {
                    HamsterSlices.slice(skin.dir, dark, it.file, it.image)
                }
                out += TextKeyboard.TextKey(
                    width = key.widthPercent,
                    label = key.label,
                    // cskin 的标签是字面值，英文模式下也要照用；只有单个字母交给大写转换。
                    fixedLabel = !key.label.isSingleAsciiLetter(),
                    keyTextSize = key.textSize,
                    roundCorner = key.cornerRadius,
                    keyTextColor = key.textColor?.toTrimeColor() ?: fallbackText,
                    keyBackColor = backImage ?: key.backColor?.toTrimeColor().orEmpty(),
                    hilitedKeyBackColor = backImagePressed ?: key.backColorPressed?.toTrimeColor().orEmpty(),
                    click = token?.let(KeyActionToken::Plain),
                    swipeUp = key.swipeUp?.let(KeyActionToken::Plain),
                    swipeDown = key.swipeDown?.let(KeyActionToken::Plain),
                )
            }
            // 去掉空键后整行未必是 100%，按比例归一，免得右侧留缺口。
            val total = out.sumOf { it.width.toDouble() }.toFloat()
            if (total > 0f && kotlin.math.abs(total - 100f) > 0.01f) {
                out.map { it.copy(width = it.width * 100f / total) }
            } else {
                out
            }
        }

        Timber.i(
            "hamster: keyboard %s keys=%s",
            id,
            keys.joinToString(" ") { "${it.label.ifBlank { "·" }}:${it.width}" },
        )
        return TextKeyboard(
            name = skin.name,
            width = 100f,
            height = rows.rows.size.toFloat(),
            columns = -1,
            keys = keys,
        )
    }

    private fun toTextKey(
        skin: HamsterSkinParser.HamsterSkin,
        dark: Boolean,
        key: HamsterSkinParser.HamsterKey,
        placed: HamsterSkinParser.PlacedKey,
        available: Set<String>,
        fallbackText: String,
    ): TextKeyboard.TextKey? {
        val token = key.click?.let { translateAction(it, available) }
        // 没有可执行动作（元书私有 shortcut 等）直接丢弃，避免留下按了没反应的键。
        if (key.click == null || (key.click.startsWith("Keyboard_") && token == null)) return null

        val backImage = key.backImage?.let { HamsterSlices.slice(skin.dir, dark, it.file, it.image) }
        val backImagePressed = key.backImagePressed?.let {
            HamsterSlices.slice(skin.dir, dark, it.file, it.image)
        }
        val foregroundImage = key.foregroundImage?.let {
            HamsterSlices.slicePlain(skin.dir, dark, it.file, it.image)
        }

        return TextKeyboard.TextKey(
            width = placed.w * 100f,
            height = placed.h * 100f,
            xPercent = placed.x,
            yPercent = placed.y,
            wPercent = placed.w,
            hPercent = placed.h,
            label = key.label,
            // cskin 的标签是字面值，英文模式下也要照用；只有单个字母交给大写转换。
            fixedLabel = !key.label.isSingleAsciiLetter(),
            labelSymbol = key.symbol,
            symbolAtTopLeft = key.symbol.isNotEmpty(),
            labelSymbolRight = key.symbolRight,
            hint = key.hint,
            // 角标与长按命令都是小字，元书大约 10pt，这里按屏幕密度用 9sp。
            symbolTextSize = if (key.symbol.isNotEmpty() || key.symbolRight.isNotEmpty() || key.hint.isNotEmpty()) 9f else 0f,
            keyTextSize = key.textSize,
            roundCorner = key.cornerRadius,
            keyTextColor = key.textColor?.toTrimeColor() ?: fallbackText,
            keyBackColor = backImage ?: key.backColor?.toTrimeColor().orEmpty(),
            hilitedKeyBackColor = backImagePressed ?: key.backColorPressed?.toTrimeColor().orEmpty(),
            click = token?.let(KeyActionToken::Plain),
            swipeUp = key.swipeUp?.let(KeyActionToken::Plain),
            swipeDown = key.swipeDown?.let(KeyActionToken::Plain),
            keyForegroundImage = foregroundImage.orEmpty(),
        )
    }

    /** 元书的键盘切换动作 → 同文预设键；目标键盘不存在时置空，避免点了没反应还丢键。 */
    private fun translateAction(token: String, available: Set<String>): String? = when (token) {
        "Keyboard_numeric" -> "Hamster_numeric".takeIf { "numeric" in available }
        "Keyboard_symbolic" -> "Hamster_symbolic".takeIf { "symbolic" in available }
        "Keyboard_default" -> "Hamster_alpha"
        else -> token
    }

    private fun String.isSingleAsciiLetter(): Boolean =
        length == 1 && this[0].code < 128 && this[0].isLetter()

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
