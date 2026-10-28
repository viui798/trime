/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.CallSuper
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.ui.common.OnItemChangedListener
import com.osfans.trime.ui.main.MainViewModel

open class SchemaListAdapter(
    items: List<SchemaItem>,
) : BaseQuickAdapter<SchemaItem, SchemaListAdapter.ViewHolder>(items) {
    var multiselect = false
        private set

    private val selected = mutableListOf<SchemaItem>()

    private var listener: OnItemChangedListener<SchemaItem>? = null

    fun removeItemChangedListener() {
        listener = null
    }

    fun addOnItemChangedListener(x: OnItemChangedListener<SchemaItem>) {
        listener = listener?.let { OnItemChangedListener.merge(it, x) } ?: x
    }

    private var onBackPressedCallback: OnBackPressedCallback? = null

    private var mainViewModel: MainViewModel? = null

    fun setViewModel(model: MainViewModel) {
        mainViewModel = model
    }

    inner class ViewHolder(
        ui: SchemaListEntryUi,
    ) : RecyclerView.ViewHolder(ui.root) {
        val checkBox = ui.checkBox
        val nameText = ui.nameText
        val subtitleText = ui.subtitleText
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(SchemaListEntryUi(context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: SchemaItem?,
    ) {
        item ?: return
        with(holder) {
            nameText.text = item.name
            subtitleText.text = item.id
            // 勾选框常驻：勾着 = 已启用；取消勾选即从启用列表移除。
            checkBox.isVisible = true
            checkBox.isChecked = if (multiselect) selected.any { it.id == item.id } else true
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (multiselect) {
                    select(item, isChecked)
                } else if (!isChecked) {
                    remove(item)
                }
            }
            nameText.setOnClickListener {
                checkBox.toggle()
            }
        }
    }

    private fun select(
        item: SchemaItem,
        shouldSelect: Boolean,
    ) {
        if (shouldSelect) {
            if (selected.indexOf(item) == -1) selected.add(item)
        } else {
            selected.remove(item)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    open fun enterMultiSelect(onBackPressedDispatcher: OnBackPressedDispatcher) {
        mainViewModel?.let {
            if (multiselect) {
                return
            }
            onBackPressedCallback =
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        exitMultiSelect()
                    }
                }
            onBackPressedDispatcher.addCallback(onBackPressedCallback!!)
            it.enableToolbarDeleteButton {
                deleteSelected()
                exitMultiSelect()
            }
            it.hideToolbarEditButton()
            multiselect = true
            notifyDataSetChanged()
        }
    }

    private fun deleteSelected() {
        if (!multiselect || selected.isEmpty()) {
            return
        }
        selected.forEach { remove(it) }
        listener?.onItemRemovedBatch(selected)
    }

    @SuppressLint("NotifyDataSetChanged")
    open fun exitMultiSelect() {
        mainViewModel?.let {
            if (!multiselect) {
                return
            }
            onBackPressedCallback?.remove()
            it.disableToolbarDeleteButton()
            multiselect = false
            selected.clear()
            notifyDataSetChanged()
            if (items.isNotEmpty()) {
                it.showToolbarEditButton()
            }
        }
    }

    private fun addInternal(
        idx: Int,
        item: SchemaItem,
    ) {
        listener?.onItemAdded(idx, item)
    }

    @CallSuper
    override fun add(data: SchemaItem, commitCallback: Runnable?) {
        super.add(data, commitCallback)
        addInternal(items.size, data)
    }

    @CallSuper
    override fun add(position: Int, data: SchemaItem, commitCallback: Runnable?) {
        super.add(position, data, commitCallback)
        addInternal(position, data)
    }

    @CallSuper
    override fun addAll(collection: Collection<SchemaItem>, commitCallback: Runnable?) {
        super.addAll(collection, commitCallback)
        listener?.onItemAddedBatch(collection.toList())
    }

    /** This also valid for [BaseQuickAdapter.remove] */
    @CallSuper
    override fun removeAt(position: Int, commitCallback: Runnable?) {
        // 先让 adapter 真正移除，再通知监听器；否则监听器里的 itemCount/标题还是旧值。
        val item = items[position]
        super.removeAt(position, commitCallback)
        listener?.onItemRemoved(position, item)
    }
}
