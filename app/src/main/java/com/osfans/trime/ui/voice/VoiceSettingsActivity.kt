/*
 * SPDX-FileCopyrightText: 2026 Zhi Trime contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import splitties.views.topPadding
import androidx.fragment.app.commit
import com.osfans.trime.R
import com.osfans.trime.data.voice.JsVoiceProviders
import com.osfans.trime.databinding.ActivityVoiceSettingsBinding
import com.osfans.trime.util.isNightMode

/**
 * 语音设置的宿主：不带参数时展示插件清单，带 [EXTRA_PLUGIN_ID] 时展示该插件的设置。
 *
 * 不用 Navigation 路由：插件 id 是动态的，塞进现有 TypeSafe 路由只会给每个插件生成一个
 * 不可预知的 destination；独立的 Activity 更直接，也方便以后单独改这一块的视觉。
 */
class VoiceSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                bottomMargin = systemBars.bottom
            }
            binding.voiceToolbar.root.topPadding = systemBars.top
            windowInsets
        }
        WindowCompat
            .getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !resources.configuration.isNightMode()
        setContentView(binding.root)
        setSupportActionBar(binding.voiceToolbar.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        supportActionBar?.title = pluginId
            ?.let { JsVoiceProviders.specOf(it)?.label }
            ?: getString(R.string.voice_input)

        if (savedInstanceState == null) {
            val fragment = if (pluginId == null) {
                VoiceSettingsFragment()
            } else {
                VoicePluginSettingsFragment.newInstance(pluginId)
            }
            supportFragmentManager.commit {
                replace(R.id.voiceSettingsContainer, fragment)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"

        fun start(
            context: Context,
            pluginId: String? = null,
        ) {
            context.startActivity(
                Intent(context, VoiceSettingsActivity::class.java).apply {
                    if (pluginId != null) putExtra(EXTRA_PLUGIN_ID, pluginId)
                },
            )
        }
    }
}
