/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.osfans.trime.TrimeApplication
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import java.io.File

object StorageLocation {
    fun switchTo(mode: DataStorageMode, externalPath: String? = null) {
        val prefs = AppPrefs.defaultInstance().profile
        val oldRoot = DataManager.rimeRootDir
        val path = externalPath ?: prefs.externalEngineRoot.getValue().ifBlank { "/storage/emulated/0/rime-cheese" }
        val target = if (mode == DataStorageMode.DIRECT_EXTERNAL) {
            val candidate = File(path).canonicalFile
            val shared = android.os.Environment.getExternalStorageDirectory().canonicalFile
            require(candidate.path.startsWith(shared.path + File.separator) &&
                candidate.relativeTo(shared).path.split(File.separator).none { it.equals("Android", true) }) {
                "请选择共享存储中的独立子目录"
            }
            check(DataManager.canUseExternalRoot(candidate)) { "请先授予所有文件访问权限" }
            candidate
        } else File(TrimeApplication.getInstance().externalFilesDir, "rime")
        if (mode == DataStorageMode.EXTERNAL_SYNC) check(UserDataManager.hasExternalAccess()) { "请先选择外部同步目录" }
        RimeDaemon.changeStorage {
            TraditionalRimeLayout.prepare(oldRoot)
            TraditionalRimeLayout.prepare(target)
            SafeDirectoryCopy.copy(oldRoot, target)
            check(prefs.dataStorageMode.sharedPreferences.edit()
                .putString(prefs.dataStorageMode.key, mode.name)
                .putString(prefs.externalEngineRoot.key, path)
                .commit()) { "无法保存存储设置" }
            DataManager.invalidateRimeRootCache()
            DataManager.setStagingDir(null)
        }
    }
}
