/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/ui/ClipboardCaptureActivity.kt。
 * 透明一次性 Activity：读系统剪贴板 → 交给前台服务入队上传。
 */
package com.osfans.trime.ui.clipsync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.ClipSyncService

class ClipboardCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val capturedContent =
            runCatching {
                val clip = clipboardManager.primaryClip ?: return@runCatching null
                if (clip.itemCount == 0) return@runCatching null
                val item = clip.getItemAt(0)
                item.uri?.toString()
                    ?: item.text?.toString()?.takeIf { it.isNotEmpty() }
                    ?: item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
            }.getOrNull()

        val messageRes =
            if (capturedContent.isNullOrBlank()) {
                R.string.clip_sync_manual_capture_empty
            } else {
                ClipSyncService.submitCapturedClipboard(this, capturedContent, "foreground-capture")
                R.string.clip_sync_manual_capture_success
            }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        finish()
    }
}
