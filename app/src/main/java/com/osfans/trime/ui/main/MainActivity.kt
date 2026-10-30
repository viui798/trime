/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.core.data.ExternalRootAutoConfig
import com.osfans.trime.core.data.RimeProfileManager
import com.osfans.trime.core.data.SchemePackage
import com.osfans.trime.core.data.SchemePackageManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.soundeffect.SoundEffectManager
import com.osfans.trime.databinding.ActivityMainBinding
import com.osfans.trime.util.toast
import com.osfans.trime.ui.setup.SetupActivity
import com.osfans.trime.util.item
import com.osfans.trime.util.isNightMode
import com.osfans.trime.util.parcelable
import com.osfans.trime.util.startActivity
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import splitties.dimensions.dp
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val uiMode by AppPrefs.defaultInstance().advanced.uiMode

    private lateinit var navController: NavController

    /** onCreate 走了「免选即外部」授权分支、还没完成创建时为 true，由 onResume 收尾。 */
    private var waitingExternalRootPermission = false
    private var testInputPanel: TestInputPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiMode =
            when (uiMode) {
                AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        AppCompatDelegate.setDefaultNightMode(uiMode)
        super.onCreate(savedInstanceState)
        // 芝版同文「免选即外部」：首次启动把引擎根指到外部固定目录，需要
        // 「所有文件访问」授权时先拦下说明，授权回来后 onResume 会补走创建。
        if (ExternalRootAutoConfig.pending() && !ExternalRootAutoConfig.permissionReady()) {
            promptExternalRootPermission()
            return
        }
        continueOnCreate()
    }

    private fun promptExternalRootPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_root_auto_title)
            .setMessage(getString(R.string.external_root_auto_message, ExternalRootAutoConfig.targetDir().absolutePath))
            .setCancelable(false)
            .setNegativeButton(R.string.external_root_auto_stay) { _, _ ->
                ExternalRootAutoConfig.markDismissed()
                continueOnCreate()
            }
            .setPositiveButton(R.string.external_root_auto_go) { _, _ ->
                waitingExternalRootPermission = true
                ExternalRootAutoConfig.openPermissionSettings(this)
            }
            .show()
    }

    private fun continueOnCreate() {
        if (ExternalRootAutoConfig.pending()) {
            // 权限已就绪：静默切换引擎根并迁移既有数据，完成后重启 Rime 在新根上重建。
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { ExternalRootAutoConfig.apply() } }
                    .onSuccess { migrated ->
                        toast(
                            getString(R.string.external_root_auto_applied, ExternalRootAutoConfig.targetDir().absolutePath) +
                                if (migrated) getString(R.string.external_root_auto_migrated_suffix) else "",
                        )
                        runCatching { RimeDaemon.restartRime(fullCheck = true) }
                            .onFailure { Timber.w(it, "restart rime after external root switch failed") }
                    }
                    .onFailure {
                        Timber.w(it, "external root auto config failed")
                        toast(getString(R.string.external_root_auto_failed))
                        // 失败不再反复打扰；用户仍可在 设置→配置 里手动配置引擎目录。
                        ExternalRootAutoConfig.markDismissed()
                    }
            }
        }
        if (SetupActivity.shouldShowUp()) {
            startActivity<SetupActivity>()
            finish()
            return
        }
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                bottomMargin = maxOf(systemBars.bottom, ime.bottom)
            }
            binding.mainToolbar.root.topPadding = systemBars.top
            windowInsets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat?>,
                ): WindowInsetsCompat {
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                    binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = maxOf(systemBars.bottom, ime.bottom)
                    }
                    return insets
                }
            },
        )
        WindowCompat
            .getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !resources.configuration.isNightMode()

        setContentView(binding.root)
        // always show toolbar back arrow icon
        binding.mainToolbar.toolbar.navigationIcon =
            DrawerArrowDrawable(this).apply {
                progress = 1f
                color = ContextCompat.getColor(this@MainActivity, R.color.settings_on_surface)
            }
        // show menu icon and other action icons on toolbar
        // don't use `setSupportActionBar(binding.toolbar)` here,
        // because navController would change toolbar title, we need to control it by ourselves
        setupToolbarMenu(binding.mainToolbar.toolbar.menu)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = NavigationRoute.createGraph(navController)
        binding.mainToolbar.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
        onBackPressedDispatcher.addCallback {
            if (binding.testInputPanel.isVisible) {
                binding.testInputPanel.dismiss()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        testInputPanel = binding.testInputPanel
        viewModel.toolbarTitle.observe(this) {
            binding.mainToolbar.toolbar.title = it
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            // 芝版同文：主页顶部已经有搜索框 + 键盘状态卡，再加上口号副标题会挤成一团，
            // 所以工具栏只留标题。
            binding.mainToolbar.toolbar.subtitle = ""
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        processIntent(intent)
        checkNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }

            Intent.ACTION_RUN -> {
                val route = intent.parcelable<NavigationRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(NavigationRoute.Main, false)
                navController.navigate(route)
            }
        }
    }

    private fun setupToolbarMenu(menu: Menu) {
        // 浅色工具栏：菜单图标统一跟正文同色，矢量图本身是白的
        val menuIconTint = ContextCompat.getColor(this, R.color.settings_on_surface)
        val optionMenuItems = listOf(
            menu.item(R.string.deploy, R.drawable.ic_baseline_refresh_reversed_24, iconTint = menuIconTint, showAsAction = true) {
                viewModel.rime.launchOnReady { it.deploy() }
            },
            menu.item(R.string.test_input, R.drawable.ic_baseline_keyboard_24, iconTint = menuIconTint, showAsAction = true) {
                testInputPanel?.show(window)
            },
            menu.item(R.string.scheme_directory) {
                showSchemeDirectoryDialog()
            },
            menu.item(R.string.developer) {
                navController.navigate(NavigationRoute.Developer)
            },
            menu.item(R.string.about) {
                navController.navigate(NavigationRoute.About)
            },
        )
        optionMenuItems.forEach { item ->
            viewModel.topOptionsMenu.observe(this) { enabled ->
                item.isVisible = enabled
            }
        }
        menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint = menuIconTint, showAsAction = true) {
            viewModel.toolbarEditButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarEditButtonVisible.observe(this@MainActivity) {
                isVisible = it
            }
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint = menuIconTint, showAsAction = true) {
            viewModel.toolbarDeleteButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarDeleteButtonOnClickListener.observe(this@MainActivity) {
                isVisible = it != null
            }
        }
        menu.forEach { item ->
            // show menu item on demand
            item.isVisible = false
        }
    }

    fun showSchemeDirectoryDialog(onDone: (() -> Unit)? = null) {
        val packages = SchemePackageManager.listPackages()
        if (packages.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scheme_directory)
                .setMessage(R.string.scheme_directory_no_package)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val currentName = RimeProfileManager.currentName()
        val current = packages.firstOrNull { it.profileName == currentName }
        // 首项固定为「默认（运行数据）」，这是从方案目录切回默认目录的入口
        val labels =
            listOf(getString(R.string.scheme_directory_default)) + packages.map { it.displayName }
        val checked =
            when {
                currentName.isBlank() -> 0
                current != null -> packages.indexOf(current) + 1
                else -> -1
            }
        val currentLabel =
            current?.displayName
                ?: if (currentName.isBlank()) {
                    getString(R.string.scheme_directory_default)
                } else {
                    currentName
                }
        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.scheme_directory)} · $currentLabel")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    switchToDefaultProfile(onDone)
                } else {
                    switchToPackage(packages[which - 1], onDone)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchToDefaultProfile(onDone: (() -> Unit)?) {
        if (!RimeProfileManager.isProfile()) return
        RimeProfileManager.switchTo("")
        toast(R.string.scheme_directory_switching)
        viewModel.rime.launchOnReady {
            UserDataManager.refreshSchemaListAfterImport()
            it.deploy()
            runOnUiThread { onDone?.invoke() }
        }
    }

    private fun switchToPackage(
        pkg: SchemePackage,
        onDone: (() -> Unit)?,
    ) {
        if (RimeProfileManager.isProfile() && RimeProfileManager.currentName() == pkg.profileName) return
        val progress =
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.scheme_directory_preparing, pkg.displayName))
                .setMessage(R.string.scheme_directory_preparing_hint)
                .setCancelable(false)
                .show()
        lifecycleScope.launch {
            val ready =
                withContext(Dispatchers.IO) {
                    runCatching { SchemePackageManager.ensureProfile(pkg) }.isSuccess
                }
            progress.dismiss()
            if (!ready) {
                toast(R.string.scheme_directory_prepare_failed)
                return@launch
            }
            RimeProfileManager.switchTo(pkg.profileName)
            toast(R.string.scheme_directory_switching)
            viewModel.rime.launchOnReady {
                UserDataManager.refreshSchemaListAfterImport()
                it.deploy()
                runOnUiThread { onDone?.invoke() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (viewModel.restartBackgroundSyncWork.value == true) {
            viewModel.restartBackgroundSyncWork.value = false
            BackgroundSyncWork.forceStart(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingExternalRootPermission) {
            // 只有真从「所有文件访问」设置页回来才检查：授权成功补走创建流程，
            // 没授权就不再打扰。首次进入（对话框还挂着）不会走到这里。
            waitingExternalRootPermission = false
            if (!ExternalRootAutoConfig.permissionReady()) {
                ExternalRootAutoConfig.markDismissed()
            }
            continueOnCreate()
            return
        }
        if (UserDataManager.isStorageAvailable()) {
            SoundEffectManager.init()
        }
    }

    override fun onStop() {
        super.onStop()
        testInputPanel?.dismiss()
    }

    override fun onDestroy() {
        testInputPanel = null
        super.onDestroy()
    }

    private fun checkNotificationPermission() {
        if (XXPermissions.isGranted(this, Permission.POST_NOTIFICATIONS)) {
            return
        } else {
            MaterialAlertDialogBuilder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    XXPermissions
                        .with(this)
                        .permission(Permission.POST_NOTIFICATIONS)
                        .request(null)
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }
}
