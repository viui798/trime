/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.annotation.SuppressLint
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedDispatcher
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.osfans.trime.R
import com.osfans.trime.ui.common.ImportFab
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.ui.common.OnItemChangedListener
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams as coreLParams
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.margin
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityEndBottom
import splitties.views.imageDrawable
import splitties.views.recyclerview.verticalLayoutManager
import splitties.views.setPaddingDp
import kotlin.math.min

class SchemaListUi(
    override val ctx: Context,
    private val initialEntries: List<SchemaItem>,
    private val contentSource: SchemaListUi.() -> Array<SchemaItem>,
    private val onImportRequested: () -> Unit = {},
    private val onDirectoryRequested: () -> Unit = {},
    private val onDeployRequested: () -> Unit = {},
) : Ui {
    private var shouldShowFab = false

    // 芝版同文：与外观页共用同一套 FAB 样式，见 ImportFab。
    private val fab =
        view(::FloatingActionButton) {
            ImportFab.decorate(this)
        }

    private var suspendUndo = false

    private fun showUndoSnackBar(
        text: String,
        action: () -> Unit,
    ) {
        if (suspendUndo) return
        Snackbar
            .make(root, text, Snackbar.LENGTH_SHORT)
            .setAction(R.string.undo) {
                suspendUndo = true
                action.invoke()
                suspendUndo = false
            }.addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onShown(transientBottomBar: Snackbar) {
                        // snackbar is invisible when it attached to parent,
                        // but change visibility won't trigger `onDependentViewChanged`.
                        // so we need to update fab position when snackbar fully shown
                        // see [^1]
                        fab.translationY = -transientBottomBar.view.height.toFloat()
                    }
                },
            ).show()
    }

    fun updateFAB() {
        updateHeader()
        updateEmptyState()
        // FAB 常驻；点开是「导入 / 方案目录 / 部署」快捷菜单。
        shouldShowFab = true
        fab.show()
        fab.setOnClickListener { showQuickMenu() }
    }

    private fun showQuickMenu() {
        var popup: PopupWindow? = null
        val menu =
            verticalLayout {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                add(
                    actionButton(R.drawable.ic_baseline_add_24, R.string.schema_action_import) {
                        popup?.dismiss()
                        showImportOrEnable()
                    },
                    coreLParams(),
                )
                add(
                    actionButton(R.drawable.ic_baseline_book_24, R.string.schema_action_directory) {
                        popup?.dismiss()
                        onDirectoryRequested()
                    },
                    coreLParams(),
                )
                add(
                    actionButton(R.drawable.ic_baseline_refresh_reversed_24, R.string.schema_action_deploy) {
                        popup?.dismiss()
                        onDeployRequested()
                    },
                    coreLParams(),
                )
            }
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup =
            PopupWindow(
                menu,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            ).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = ctx.dp(8).toFloat()
            }
        val location = IntArray(2)
        fab.getLocationOnScreen(location)
        popup.showAtLocation(
            root,
            Gravity.NO_GRAVITY,
            location[0] + fab.width - menu.measuredWidth,
            location[1] - menu.measuredHeight - ctx.dp(8),
        )
    }

    private fun actionButton(
        icon: Int,
        label: Int,
        onClick: () -> Unit,
    ): MaterialButton =
        MaterialButton(ctx).apply {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(styledColor(com.google.android.material.R.attr.colorOnSurface))
            setText(label)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = ctx.dp(2)
            iconSize = ctx.dp(24)
            cornerRadius = ctx.dp(18)
            insetTop = ctx.dp(10)
            insetBottom = ctx.dp(10)
            minWidth = ctx.dp(76)
            minHeight = ctx.dp(76)
            isAllCaps = false
            textSize = 12f
            backgroundTintList =
                ColorStateList.valueOf(
                    styledColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                )
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
            setOnClickListener { onClick() }
        }

    private fun showImportOrEnable() {
        val source = contentSource(this)
        if (source.isEmpty()) {
            onImportRequested()
            return
        }
        val items = source.map { it.name }.toTypedArray()
        val checked = BooleanArray(items.size) { false }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.enable_schemata)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val collection = source.filterIndexed { index, _ -> checked[index] }
                if (collection.isEmpty()) return@setPositiveButton
                if (collection.size == 1) {
                    adapter.add(collection[0])
                } else {
                    adapter.addAll(collection)
                }
            }.setNeutralButton(R.string.schema_list_import) { _, _ ->
                onImportRequested()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    val adapter =
        object : SchemaListAdapter(initialEntries) {
            init {
                addOnItemChangedListener(
                    object : OnItemChangedListener<SchemaItem> {
                        override fun onItemAdded(
                            idx: Int,
                            item: SchemaItem,
                        ) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.added_x, item.name)) {
                                remove(item)
                            }
                        }

                        override fun onItemRemoved(
                            idx: Int,
                            item: SchemaItem,
                        ) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.removed_x, item.name)) {
                                add(idx, item)
                            }
                        }

                        override fun onItemAddedBatch(items: List<SchemaItem>) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.added_n_items, items.size)) {
                                items.forEach { remove(it) }
                            }
                        }

                        override fun onItemRemovedBatch(items: List<SchemaItem>) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.removed_n_items, items.size)) {
                                items.forEach { add(it) }
                            }
                        }
                    },
                )
            }

            override fun enterMultiSelect(onBackPressedDispatcher: OnBackPressedDispatcher) {
                if (shouldShowFab) {
                    fab.hide()
                }
                super.enterMultiSelect(onBackPressedDispatcher)
            }

            override fun exitMultiSelect() {
                if (shouldShowFab) {
                    fab.show()
                }
                super.exitMultiSelect()
            }
        }

    private val list =
        recyclerView {
            layoutManager = verticalLayoutManager()
            adapter = this@SchemaListUi.adapter
            clipToPadding = false
            setPaddingDp(0, 12, 0, 16)
            addItemDecoration(SchemaListDecoration(ctx))
        }

    private val headerTitle =
        textView {
            setPaddingDp(20, 16, 20, 2)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
        }

    private val headerHint =
        textView {
            setPaddingDp(20, 0, 20, 10)
            textSize = 13f
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            text = ctx.getString(R.string.schema_list_enabled_hint)
        }

    private val header =
        verticalLayout {
            add(headerTitle, coreLParams(matchParent, wrapContent))
            add(headerHint, coreLParams(matchParent, wrapContent))
        }

    private val content =
        verticalLayout {
            add(header, coreLParams(matchParent, wrapContent))
            add(list, coreLParams(matchParent, matchParent))
        }

    /** 芝版同文：不再预置方案，空列表必须说清楚原因，并就地给一个导入入口。 */
    private val emptyView =
        View
            .inflate(ctx, R.layout.view_schema_empty, null)
            .apply {
                findViewById<Button>(R.id.empty_import).setOnClickListener { onImportRequested() }
            }

    private var deploying = false

    init {
        updateFAB()
        updateEmptyState()
    }

    private fun updateHeader() {
        headerTitle.text = ctx.getString(R.string.schema_list_enabled_title, adapter.itemCount)
    }

    /** 导入之后、部署完成之前：把空状态换成「正在部署」，别让用户以为没反应。 */
    fun showDeploying() {
        deploying = true
        emptyView.findViewById<TextView>(R.id.empty_title).text =
            ctx.getString(R.string.schema_list_deploying_title)
        emptyView.findViewById<TextView>(R.id.empty_hint).text =
            ctx.getString(R.string.schema_list_deploying_hint)
        emptyView.findViewById<Button>(R.id.empty_import).isVisible = false
        emptyView.isVisible = true
        shouldShowFab = false
        fab.hide()
    }

    private fun updateEmptyState() {
        if (deploying) return
        emptyView.isVisible = adapter.itemCount == 0
    }

    private fun updateViewMargin(insets: WindowInsetsCompat? = null) {
        val windowInsets = (insets ?: ViewCompat.getRootWindowInsets(root)) ?: return
        val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
        fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBars.bottom + ctx.dp(ImportFab.MARGIN_DP)
        }
        list.bottomPadding = navBars.bottom
    }

    override val root =
        coordinatorLayout {
            backgroundColor = styledColor(com.google.android.material.R.attr.colorSurface)
            add(
                content,
                defaultLParams {
                    height = matchParent
                    width = matchParent
                },
            )
            add(
                emptyView,
                defaultLParams {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                },
            )
            add(
                fab,
                defaultLParams {
                    gravity = gravityEndBottom
                    margin = dp(ImportFab.MARGIN_DP)
                    behavior =
                        object : HideBottomViewOnScrollBehavior<FloatingActionButton>() {
                            @SuppressLint("RestrictedApi")
                            override fun layoutDependsOn(
                                parent: CoordinatorLayout,
                                child: FloatingActionButton,
                                dependency: View,
                            ): Boolean = dependency is Snackbar.SnackbarLayout

                            override fun onDependentViewChanged(
                                parent: CoordinatorLayout,
                                child: FloatingActionButton,
                                dependency: View,
                            ): Boolean {
                                // [^1]: snackbar is invisible when it attached to parent
                                // update fab position only when snackbar is visible
                                if (dependency.isVisible) {
                                    child.translationY = min(0f, dependency.translationY - dependency.height)
                                    return true
                                }
                                return false
                            }

                            override fun onDependentViewRemoved(
                                parent: CoordinatorLayout,
                                child: FloatingActionButton,
                                dependency: View,
                            ) {
                                child.translationY = 0f
                            }
                        }
                },
            )
            doOnAttach { updateViewMargin() }
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                updateViewMargin(windowInsets)
                windowInsets
            }
            updateFAB()
            updateEmptyState()
            adapter.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() = updateEmptyState()

                    override fun onItemRangeInserted(
                        positionStart: Int,
                        itemCount: Int,
                    ) = updateEmptyState()

                    override fun onItemRangeRemoved(
                        positionStart: Int,
                        itemCount: Int,
                    ) = updateEmptyState()
                },
            )
        }
}
