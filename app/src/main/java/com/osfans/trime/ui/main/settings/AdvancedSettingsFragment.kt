/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.update.ChecksumMismatchException
import com.osfans.trime.data.update.ChecksumMissingException
import com.osfans.trime.data.update.GitHubAsset
import com.osfans.trime.data.update.GitHubRelease
import com.osfans.trime.data.update.UpdateManager
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import splitties.dimensions.dp

class AdvancedSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().advanced) {

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode

    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        val category =
            PreferenceCategory(ctx).apply {
                title = getString(R.string.update_category)
                isIconSpaceReserved = false
            }
        screen.addPreference(category)
        category.addPreference(
            Preference(ctx).apply {
                key = "check_update"
                title = getString(R.string.check_update)
                summary = getString(R.string.check_update_summary, BuildConfig.VERSION_NAME)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    checkForUpdates()
                    true
                }
            },
        )
    }

    private fun checkForUpdates() {
        val ctx = requireContext()
        val checking =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.check_update)
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { UpdateManager.fetchLatest() }
            runCatching { checking.dismiss() }
            if (!isAdded) return@launch
            val release = result.getOrNull()
            if (release == null) {
                val reason = result.exceptionOrNull()?.let { it.localizedMessage ?: it.javaClass.simpleName } ?: "unknown"
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.check_update)
                    .setMessage(getString(R.string.update_check_failed, reason))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            if (!UpdateManager.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.check_update)
                    .setMessage(getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val asset = UpdateManager.pickAsset(release)
            if (asset == null) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.check_update)
                    .setMessage(R.string.update_no_asset)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            showReleaseDialog(release, asset)
        }
    }

    private fun showReleaseDialog(
        release: GitHubRelease,
        asset: GitHubAsset,
    ) {
        val ctx = requireContext()
        val notes =
            TextView(ctx).apply {
                text = UpdateManager.formatReleaseNotes(release.body).ifBlank { getString(R.string.update_no_notes) }
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(8))
            }
        val scroll =
            ScrollView(ctx).apply {
                addView(notes, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                isFillViewport = true
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        minOf(ctx.dp(340), (resources.displayMetrics.heightPixels * 0.42f).toInt()),
                    )
            }
        val browser =
            TextView(ctx).apply {
                setText(R.string.update_open_release)
                textSize = 14f
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(ctx, R.color.colorPrimary))
                setPadding(ctx.dp(24), 0, ctx.dp(24), ctx.dp(4))
            }
        val actions =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(8))
            }
        val cancel =
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(android.R.string.cancel)
            }
        val download =
            MaterialButton(ctx).apply {
                setText(R.string.update_download)
            }
        actions.addView(
            cancel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            download,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dp(12)
            },
        )
        val content =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll)
                addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        val dialog =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.update_available_title, release.tag))
                .setView(content)
                .create()
        cancel.setOnClickListener { dialog.dismiss() }
        download.setOnClickListener {
            dialog.dismiss()
            downloadAndInstall(release, asset)
        }
        browser.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.releasePage(release.tag))))
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun downloadAndInstall(
        release: GitHubRelease,
        asset: GitHubAsset,
    ) {
        val ctx = requireContext()
        val label =
            TextView(ctx).apply {
                text = getString(R.string.update_downloading, asset.name)
                setPadding(0, 0, 0, ctx.dp(12))
            }
        val bar =
            ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
            }
        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), 0)
                addView(label)
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        val progress =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.check_update)
                .setView(container)
                .setCancelable(false)
                .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file =
                    UpdateManager.download(ctx, release, asset) { downloaded, total ->
                        if (!isAdded) return@download
                        if (total > 0) {
                            bar.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        }
                        label.text = getString(R.string.update_downloading, asset.name)
                    }
                runCatching { progress.dismiss() }
                if (!isAdded) return@launch
                if (!UpdateManager.canInstall(ctx)) {
                    UpdateManager.openInstallPermission(ctx)
                    ctx.toast(R.string.update_install_permission)
                    return@launch
                }
                if (!UpdateManager.install(ctx, file)) {
                    ctx.toast(R.string.update_install_failed)
                }
            } catch (e: ChecksumMismatchException) {
                runCatching { progress.dismiss() }
                ctx.toast(R.string.update_checksum_failed)
            } catch (e: ChecksumMissingException) {
                runCatching { progress.dismiss() }
                ctx.toast(R.string.update_checksum_missing)
            } catch (e: Exception) {
                runCatching { progress.dismiss() }
                ctx.toast(
                    getString(
                        R.string.update_download_failed,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"

        fun showAppIcon(context: Context, enable: Boolean) {
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SETTINGS_ACTIVITY_NAME),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
