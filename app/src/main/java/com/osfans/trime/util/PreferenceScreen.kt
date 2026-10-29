/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.core.widget.doAfterTextChanged
import com.osfans.trime.R
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import splitties.resources.drawable

fun PreferenceScreen.addCategory(title: String, block: PreferenceCategory.() -> Unit) {
    val category = PreferenceCategory(context).apply {
        setTitle(title)
    }
    addPreference(category)
    block.invoke(category)
}

fun PreferenceScreen.addCategory(@StringRes title: Int, block: PreferenceCategory.() -> Unit) {
    addCategory(context.getString(title), block)
}

fun Preference.setup(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    isSingleLineTitle = false
    setTitle(title)
    setSummary(summary)
    if (icon == null) {
        isIconSpaceReserved = false
    } else {
        setIcon(
            context.drawable(icon)?.apply {
                mutate()
                setTint(androidx.core.content.ContextCompat.getColor(context, R.color.settings_icon_fg))
            },
        )
    }
    onClick?.also {
        // 中州鹅式版式：可点进去的行才带右侧箭头
        setLayoutResource(R.layout.preference_settings_link_row)
        setOnPreferenceClickListener { _ ->
            it.invoke()
            true
        }
    }
}

/** 主页顶部的搜索框：只负责把输入回传，过滤逻辑由页面自己做。 */
class SearchPreference(
    context: Context,
    private val onQuery: (String) -> Unit,
) : Preference(context) {
    init {
        isPersistent = false
        isIconSpaceReserved = false
        isSelectable = false
        layoutResource = R.layout.preference_settings_search
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView
            .findViewById<EditText>(R.id.settings_search_input)
            ?.doAfterTextChanged { onQuery(it?.toString().orEmpty()) }
    }
}

fun PreferenceGroup.addPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(Preference(context).apply { setup(title, summary, icon, onClick) })
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    summary: String,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(context.getString(title), summary, icon, onClick)
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(context.getString(title), summary?.let(context::getString), icon, onClick)
}

class LongClickPreference(context: Context) : Preference(context) {
    private var onLongClick: (() -> Unit)? = null

    fun setOnPreferenceLongClickListener(callback: (() -> Unit)? = null) {
        onLongClick = callback
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke()
            true
        }
    }
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    addPreference(
        LongClickPreference(context).apply {
            setup(context.getString(title), summary?.let { context.getString(it) }, icon, onClick)
            setOnPreferenceLongClickListener(onLongClick)
        },
    )
}
