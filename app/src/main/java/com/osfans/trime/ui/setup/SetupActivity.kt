/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.RimeDataLayout
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.importer.RimeResourceImporter
import com.osfans.trime.data.theme.ThemeFilesManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.databinding.ActivitySetupBinding
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.setup.SetupPage.Companion.firstUndonePage
import com.osfans.trime.ui.setup.SetupPage.Companion.isLastPage
import com.osfans.trime.util.appContext
import com.osfans.trime.util.createNotificationChannel
import com.osfans.trime.util.startActivity
import com.osfans.trime.util.toast
import io.github.whiredplanck.storageaccess.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager

class SetupActivity : FragmentActivity() {
    private lateinit var viewPager: ViewPager2

    private lateinit var stepLabel: android.widget.TextView
    private lateinit var stepBar: android.widget.ProgressBar
    private lateinit var skipButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    private val storageAccess = StorageAccess(this)

    /** 只在真正需要部署时才拉起 Rime，避免进向导就吃掉一份内存。 */
    private val rime by lazy { RimeDaemon.createSession(javaClass.name) }

    /** 部署可能跑很久（大词库要编几分钟），放到 Rime 自己的 scope 里，别跟着 Activity 死。 */
    private var deployJob: Job? = null

    /** 记录是哪个页面触发的文件选择：方案要部署，皮肤不用。 */
    private var importingPage: SetupPage? = null

    private val resourcePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handlePickedResources(uris)
        }

    /** 由 [SetupPage.Schema] / [SetupPage.Skin] 的按钮调用。 */
    fun pickRimeResources() {
        importingPage = SetupPage.entries.getOrNull(viewPager.currentItem)
        // 不限定 MIME：方案包是 zip，皮肤是 yaml，各家文件管理器给的类型也不一致
        runCatching { resourcePicker.launch(arrayOf("*/*")) }
            .onFailure { toast(R.string.setup__import_no_picker) }
    }

    private fun handlePickedResources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val page = importingPage
        lifecycleScope.launch {
            toast(R.string.setup__import_busy)
            val dest =
                if (page == SetupPage.Schema) {
                    RimeDataLayout.schemaDir
                } else {
                    RimeDataLayout.themeDir
                }
            val result =
                runCatching {
                    RimeResourceImporter.import(this@SetupActivity, uris, dest)
                }.getOrElse {
                    toast(R.string.setup__import_failed)
                    return@launch
                }

            if (result.isEmpty) {
                toast(R.string.setup__import_empty)
                return@launch
            }

            // 摊平到「运行数据」，Rime 与主题扫描才看得见
            RimeDataLayout.mirror()
            // 方案：按需重建 default.custom.yaml（包自带的不动）
            UserDataManager.refreshSchemaListAfterImport()
            // 皮肤：直接切过去。一个包里常带多个变体，取名字最短的当基础款
            if (page == SetupPage.Skin) {
                ThemeFilesManager
                    .listThemes(DataManager.userDataDir)
                    .minByOrNull { it.configId.length }
                    ?.let { ThemeManager.prefs.selectedTheme.setValue(it.configId) }
            }

            // 先刷新界面：大词库部署要几分钟，不能让用户干等着没反应
            refreshCurrentFragment()
            updateButtons()

            when {
                result.skipped > 0 ->
                    toast(getString(R.string.setup__import_partial, result.files, result.skipped))
                else -> toast(getString(R.string.setup__import_ok, result.files))
            }

            if (page == SetupPage.Schema) {
                startDeploy()
            }
        }
    }

    /** 后台部署，完成后单独提示；不阻塞向导，也不随 Activity 销毁而中断。 */
    private fun startDeploy() {
        if (deployJob?.isActive == true) return
        deployJob =
            rime.lifecycleScope.launch {
                val ok = runCatching { rime.runOnReady { deploy() } }.isSuccess
                withContext(Dispatchers.Main) {
                    toast(if (ok) R.string.setup__deploy_ok else R.string.setup__deploy_failed)
                }
            }
    }

    fun pickDataPath() {
        lifecycleScope.launch {
            val picked = storageAccess.pickDirectory() ?: return@launch
            runCatching {
                UserDataManager.persistTreeUri(picked.uri)
                check(UserDataManager.sync())
                refreshCurrentFragment()
                updateButtons()
                toast(R.string.setup__data_path_imported)
                skipButton.visibility = View.VISIBLE
            }.onFailure {
                // Keep the picked folder: the user is not forced to pick again, and a folder that
                // keeps failing makes the deploy path fall back to app-specific storage on its own.
                refreshCurrentFragment()
                updateButtons()
                toast(R.string.setup__data_path_import_failed)
            }
        }
    }

    fun refreshCurrentFragment() {
        val fragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
        (fragment as? SetupFragment)?.sync()
    }

    private fun completeSetup() {
        startActivity<MainActivity>()
        finish()
    }

    companion object {
        private var shown = false
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 87463

        fun shouldShowUp() = !shown && !isSkipped() && SetupPage.hasUndonePage()

        private const val SETUP_PREFS = "zhi_setup"
        private const val KEY_SKIPPED = "skipped"

        /**
         * 芝版同文：用户点过「跳过」就不再每次冷启动都弹向导。
         *
         * 上游的逻辑是「必填页没完成就重新弹」，而必填页里有「启用输入法」「设为默认」，
         * 用户当时不启用就会一直弹，非常烦。方案页现在有空状态和导入入口，不怕用户找不到。
         */
        fun isSkipped(): Boolean =
            appContext
                .getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SKIPPED, false)

        fun markSkipped() {
            appContext
                .getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_SKIPPED, true) }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySetupBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                sysBars.left,
                sysBars.top,
                sysBars.right,
                sysBars.bottom,
            )
            windowInsets
        }
        setContentView(binding.root)
        stepLabel = binding.setupStepLabel
        stepBar = binding.setupStepBar
        skipButton = binding.skipButton.apply {
            text = getString(R.string.setup__skip)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SetupActivity)
                    .setMessage(R.string.setup__skip_hint)
                    // 芝版同文：「不再显示」= 永久关掉向导；「好的，谢谢」只跳过这一次，
                    // 下次冷启动若还有未完成步骤会再次出现。
                    .setNeutralButton(R.string.setup__skip_hint_never) { _, _ ->
                        markSkipped()
                        completeSetup()
                    }.setPositiveButton(R.string.setup__skip_hint_yes) { _, _ ->
                        completeSetup()
                    }.setNegativeButton(R.string.setup__skip_hint_no, null)
                    .show()
            }
        }
        prevButton =
            binding.prevButton.apply {
                text = getString(R.string.setup__prev)
                setOnClickListener { viewPager.currentItem -= 1 }
            }
        nextButton =
            binding.nextButton.apply {
                setOnClickListener {
                    if (viewPager.currentItem != SetupPage.entries.size - 1) {
                        viewPager.currentItem += 1
                    } else {
                        completeSetup()
                    }
                }
            }
        viewPager = binding.viewpager
        viewPager.adapter = Adapter()
        viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateButtons()
            },
        )
        // Skip to undone page
        firstUndonePage()?.let { viewPager.currentItem = it.ordinal }
        updateButtons()
        shown = true
        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.setup_channel),
        )
    }

    fun updateButtons() {
        val allDone = !SetupPage.hasUndonePage()
        val modeSetupDone = SetupPage.Mode.isDone()
        val isFirstPage = viewPager.currentItem == 0
        val isLastPage = viewPager.currentItem.isLastPage()

        viewPager.isUserInputEnabled = modeSetupDone

        prevButton.isGone = isFirstPage
        skipButton.isGone = !modeSetupDone || allDone
        nextButton.text = getString(if (isLastPage) R.string.done else R.string.setup__next)
        val step = viewPager.currentItem + 1
        val total = SetupPage.entries.size
        stepLabel.text = getString(R.string.setup__step_progress, step, total)
        stepBar.progress = step * 100 / total
        nextButton.isGone = isLastPage && !allDone
        nextButton.isEnabled = modeSetupDone
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        for (fragment in supportFragmentManager.fragments) {
            if (fragment.isVisible) (fragment as? SetupFragment)?.sync()
        }
        updateButtons()
    }

    override fun onPause() {
        if (SetupPage.hasUndonePage()) {
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_trime_status)
                .setContentTitle(getText(R.string.trime_app_name))
                .setContentText(getText(R.string.setup__notify_hint))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        }
        super.onPause()
    }

    override fun onResume() {
        notificationManager.cancel(NOTIFY_ID)
        super.onResume()
    }

    override fun onDestroy() {
        // 部署还没跑完就销毁会话会把 Rime 掐在半路，交给 IME 接手即可
        if (deployJob?.isActive != true) {
            RimeDaemon.destroySession(javaClass.name)
        }
        super.onDestroy()
    }

    private inner class Adapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment = SetupFragment().apply {
            arguments = bundleOf("page" to SetupPage.entries[position])
        }
    }
}
