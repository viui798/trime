/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.osfans.trime.data.voice.VoiceAudioCapture

/**
 * 输入法服务自己不能弹运行时权限对话框，只能借一个透明 Activity 代劳。
 *
 * 结果直接回调 [VoiceInputManager]（同进程），拿到授权后会继续用户刚才发起的那次识别。
 */
class VoicePermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceAudioCapture.hasPermission(this)) {
            VoiceInputManager.onPermissionResult(true)
            finish()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        VoiceInputManager.onPermissionResult(granted)
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 0x5601
    }
}
