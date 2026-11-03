/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.settings.font

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeAssets
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FontFilesFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
    }

    private fun refreshFiles() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                FontOverrides.availableFonts().mapNotNull { name -> ThemeAssets.resolveFont(name)?.let { name to it } }
            }
            if (!isAdded) return@launch
            preferenceScreen.removeAll()
            files.forEach { (name, file) ->
                preferenceScreen.addPreference(object : Preference(requireContext()) {
                    init {
                        title = name
                        isIconSpaceReserved = false
                        isSelectable = false
                        widgetLayoutResource = R.layout.preference_font_actions
                    }
                    override fun onBindViewHolder(holder: PreferenceViewHolder) {
                        super.onBindViewHolder(holder)
                        holder.findViewById(R.id.font_file_actions).apply {
                            isEnabled = true
                            contentDescription = name
                            setOnClickListener { showActions(it, name, file) }
                        }
                    }
                })
            }
            if (files.isEmpty()) preferenceScreen.addPreference(Preference(requireContext()).apply {
                setSummary(R.string.font_empty_catalog)
                isSelectable = false
                isIconSpaceReserved = false
            })
        }
    }

    private fun userFile(file: File): Boolean = runCatching {
        val root = DataManager.userDataDir.canonicalFile
        file.canonicalPath.startsWith(root.path + File.separator) &&
            !FontOverrides.isSymbolicLink(file)
    }.getOrDefault(false)

    private fun showActions(anchor: View, name: String, file: File) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.font_file_info).setOnMenuItemClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.font_file_info)
                    .setMessage(getString(R.string.font_file_details, name, file.extension.uppercase(),
                        android.text.format.Formatter.formatFileSize(requireContext(), file.length()), file.absolutePath))
                    .setPositiveButton(R.string.ok, null).show()
                true
            }
            menu.add(R.string.delete).apply {
                isEnabled = userFile(file)
                setOnMenuItemClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.font_delete_confirm, name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> deleteFont(name, file) }.show()
                    true
                }
            }
            show()
        }
    }

    private fun deleteFont(name: String, file: File) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { userFile(file) && file.delete() }
            if (!isAdded) return@launch
            if (deleted) {
                FontOverrides.removeFont(name)
                ThemeManager.notifyRenderingChanged()
                refreshFiles()
            } else Toast.makeText(requireContext(), R.string.font_delete_failed, Toast.LENGTH_LONG).show()
        }
    }
}
