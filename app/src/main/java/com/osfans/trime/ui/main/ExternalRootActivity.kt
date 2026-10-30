/* SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.core.data.ExternalRootAutoConfig
import com.osfans.trime.core.data.StorageLocation
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.databinding.ActivityExternalRootBinding
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 直接使用外部目录的管理页（对齐咩版 ExternalDataActivity）。
 *
 * 可编辑外部数据目录、查看/授予「所有文件访问」权限，并把引擎根切到该目录。
 * 由存储向导页「选择外部数据目录…」在选中「直接使用外部（旧版方式）」时打开。
 */
class ExternalRootActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExternalRootBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExternalRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.externalRootToolbar.setNavigationOnClickListener { finish() }

        val current = AppPrefs.defaultInstance().profile.externalEngineRoot.getValue()
            .ifBlank { ExternalRootAutoConfig.targetDir().absolutePath }
        binding.externalRootPath.setText(savedInstanceState?.getString("path") ?: current)

        binding.externalRootGrant.setOnClickListener { ExternalRootAutoConfig.openPermissionSettings(this) }
        binding.externalRootSwitch.setOnClickListener { activate() }

        renderPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("path", binding.externalRootPath.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) renderPermission()
    }

    private fun renderPermission() {
        binding.externalRootStatus.text =
            if (ExternalRootAutoConfig.permissionReady()) getString(R.string.external_root_perm_on)
            else getString(R.string.external_root_perm_off)
    }

    private fun activate() {
        if (busy) return
        if (!ExternalRootAutoConfig.permissionReady()) {
            ExternalRootAutoConfig.openPermissionSettings(this)
            return
        }
        val target = binding.externalRootPath.text?.toString().orEmpty().trim()
        if (target.isEmpty()) {
            binding.externalRootPath.error = getString(R.string.external_root_path_label)
            return
        }
        busy = true
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_root_switch_title)
            .setMessage(R.string.external_root_switching)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { StorageLocation.switchTo(DataStorageMode.DIRECT_EXTERNAL, target) }
            }
            busy = false
            if (!isFinishing && !isDestroyed) runCatching { progress.dismiss() }
            result.onSuccess {
                toast(R.string.external_root_switch_ok)
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                if (!isFinishing && !isDestroyed) {
                    MaterialAlertDialogBuilder(this@ExternalRootActivity)
                        .setTitle(R.string.external_root_switch_fail)
                        .setMessage(error.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }
}
