/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.osfans.trime.R
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.core.data.RimeDataLayout
import com.osfans.trime.core.data.RimeProfileManager
import com.osfans.trime.core.data.SchemePackageManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.importer.RimeResourceImporter
import com.osfans.trime.ui.common.OnItemChangedListener
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.settings.ProgressFragment
import com.osfans.trime.util.NaiveDustman
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import timber.log.Timber

class SchemaListFragment :
    ProgressFragment(),
    OnItemChangedListener<SchemaItem> {
    private lateinit var ui: SchemaListUi

    private val dustman = NaiveDustman<SchemaItem>()

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            importSchemas(uris)
        }

    /**
     * 芝版同文：装完应用后向导不会再出现，这里是唯一的导入入口。
     * 导入 → 重建 schema_list → 部署 → 重挂 Fragment 让列表重新读一次。
     */
    private fun importSchemas(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            ctx.toast(R.string.setup__import_busy)
            val packagesBefore = SchemePackageManager.listPackages().map { it.dir.name }.toSet()
            // 方案包一律进全局「方案/」，方案库目录只是生成出来的运行副本。
            val dest = RimeDataLayout.schemaDir
            val result =
                runCatching {
                    RimeResourceImporter.import(ctx, uris, dest, flat = false)
                }.getOrElse {
                    ctx.toast(R.string.setup__import_failed)
                    return@launch
                }
            if (result.isEmpty) {
                ctx.toast(R.string.setup__import_empty)
                return@launch
            }
            RimeDataLayout.mirror()
            UserDataManager.refreshSchemaListAfterImport()
            ctx.toast(
                if (result.skipped > 0) {
                    ctx.getString(R.string.setup__import_partial, result.files, result.skipped)
                } else {
                    ctx.getString(R.string.setup__import_ok, result.files)
                },
            )
            // 新包不会混进当前目录：导入后直接让用户去「方案目录」选。
            val newPackages =
                SchemePackageManager.listPackages().filter { it.dir.name !in packagesBefore }
            if (newPackages.isNotEmpty()) {
                (activity as? MainActivity)?.showSchemeDirectoryDialog {
                    if (isAdded && view != null) reload()
                }
                return@launch
            }
            if (::ui.isInitialized) ui.showDeploying()
            val deployed = runCatching { rime.runOnReady { deploy() } }.isSuccess
            ctx.toast(if (deployed) R.string.setup__deploy_ok else R.string.setup__deploy_failed)
            // 部署完重跑一次 initialize()，重新向 Rime 取方案列表
            if (isAdded && view != null) reload()
        }
    }

    override suspend fun initialize(): View {
        val available = rime.runOnReady { availableSchemata().toSet() }
        val enabled = rime.runOnReady { enabledSchemata().map { it.id } }
        val entries = available.filter { enabled.contains(it.id) }
        ui =
            SchemaListUi(
                requireContext(),
                initialEntries = entries,
                contentSource = { (available - adapter.items.toSet()).toTypedArray() },
                onImportRequested = {
                    runCatching { importPicker.launch(arrayOf("*/*")) }
                        .onFailure { requireContext().toast(R.string.setup__import_no_picker) }
                },
                onDirectoryRequested = {
                    (requireActivity() as? MainActivity)?.showSchemeDirectoryDialog {
                        if (isAdded && view != null) reload()
                    }
                },
                onDeployRequested = {
                    val ctx = requireContext()
                    viewModel.viewModelScope.launch {
                        val deployed = runCatching { rime.runOnReady { deploy() } }.isSuccess
                        ctx.toast(if (deployed) R.string.setup__deploy_ok else R.string.setup__deploy_failed)
                        if (isAdded && view != null) reload()
                    }
                },
            )
        resetDustman()
        ui.adapter.setViewModel(viewModel)
        ui.adapter.addOnItemChangedListener(this)
        // 勾选框本身就是启用/停用开关，不再需要工具栏的「编辑」多选。
        viewModel.disableToolbarEditButton()
        return ui.root
    }

    override fun onStart() {
        super.onStart()
        if (::ui.isInitialized) {
            viewModel.disableToolbarEditButton()
        }
    }

    override fun onStop() {
        persistSchemaList()
        if (::ui.isInitialized) {
            ui.adapter.exitMultiSelect()
        }
        viewModel.disableToolbarEditButton()
        super.onStop()
    }

    override fun onDestroy() {
        if (::ui.isInitialized) {
            ui.adapter.removeItemChangedListener()
        }
        super.onDestroy()
    }

    override fun onItemAdded(
        idx: Int,
        item: SchemaItem,
    ) {
        dustman.addOrUpdate(item.toString(), item)
    }

    override fun onItemRemoved(
        idx: Int,
        item: SchemaItem,
    ) {
        dustman.remove(item.toString())
    }

    override fun onItemAddedBatch(items: List<SchemaItem>) {
        items.forEach { dustman.addOrUpdate(it.toString(), it) }
    }

    override fun onItemRemovedBatch(items: List<SchemaItem>) {
        items.forEach { dustman.remove(it.toString()) }
    }

    private fun persistSchemaList() {
        if (!dustman.dirty) return
        val schemaIds = ui.adapter.items.map { it.id }.toTypedArray()
        resetDustman()
        Timber.d("Persisting schema list: ${schemaIds.joinToString()}")
        viewModel.viewModelScope.launch {
            viewModel.rime.runOnReady {
                setEnabledSchemata(schemaIds)
                deploy()
                UserDataManager.sync()
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.adapter.items.associateBy { it.toString() })
    }
}
