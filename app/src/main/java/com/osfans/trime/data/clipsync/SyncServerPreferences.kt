/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/SyncServerPreferences.kt（键名改为本地 clip_sync_* 惯例；
 * 本地历史上从未写过不带协议的隐式 HTTP 地址，迁移逻辑保留但默认表为空）。
 */
package com.osfans.trime.data.clipsync

import android.content.SharedPreferences
import com.osfans.trime.data.clipsync.network.SyncServerAddress

/** Make existing implicit HTTP choices explicit once, before changing new-address defaults. */
internal object SyncServerPreferences {
    private const val VERSION = "clip_sync_server_scheme_version"
    private val profiles = listOf("syncclipboard", "oneclip", "clipcascade", "custom")
    private val legacyDefaults = emptyMap<String, String>()
    private val addressKeys = listOf(SERVER_ADDRESS_KEY) + profiles.map { "clip_sync_server_address_$it" }

    const val SERVER_PROFILE_TYPE_KEY = "clip_sync_server_profile_type"
    const val SERVER_ADDRESS_KEY = "clip_sync_server_address"
    const val USERNAME_KEY = "clip_sync_username"
    const val PASSWORD_KEY = "clip_sync_password"
    const val QUICK_SYNC_KEY = "clip_sync_enabled"
    private const val DOWNLOAD_PATH_KEY = "clip_sync_download_path"
    private const val DOWNLOAD_PATH_URI_KEY = "clip_sync_download_path_uri"
    private const val LEGACY_STORAGE_URI_KEY = "clip_sync_storage_uri"

    @Synchronized
    fun migrate(prefs: SharedPreferences) {
        // 旧版基础移植只有「应用内目录 / clip_sync_storage_uri」，迁到上游的
        // download_path（显示名）+ download_path_uri（可持久化 SAF tree URI）模型。
        if (runCatching { prefs.getString(DOWNLOAD_PATH_URI_KEY, null) }.getOrNull().isNullOrBlank()) {
            val legacy = runCatching { prefs.getString(LEGACY_STORAGE_URI_KEY, null) }.getOrNull()
            if (!legacy.isNullOrBlank()) {
                prefs
                    .edit()
                    .putString(DOWNLOAD_PATH_URI_KEY, legacy)
                    .putString(DOWNLOAD_PATH_KEY, legacy)
                    .apply()
            }
        }
        if (runCatching { prefs.getInt(VERSION, 0) }.getOrDefault(0) >= 1) return
        val snapshot = prefs.all
        val hadConfiguration =
            snapshot.keys.any {
                it in addressKeys || it == SERVER_PROFILE_TYPE_KEY ||
                    it == USERNAME_KEY || it == PASSWORD_KEY || it == QUICK_SYNC_KEY
            }
        val editor = prefs.edit()
        val migrated =
            addressKeys.associateWith { key ->
                val value = (snapshot[key] as? String)?.trim().orEmpty()
                if (value.isNotEmpty() && !value.contains("://")) {
                    runCatching { SyncServerAddress.normalize(value, "http") }.getOrDefault(value).also {
                        editor.putString(key, it)
                    }
                } else {
                    value
                }
            }
        if (hadConfiguration) {
            val current = migrated.getValue(SERVER_ADDRESS_KEY)
            val previousProfile = snapshot[SERVER_PROFILE_TYPE_KEY] as? String
            val profile =
                previousProfile?.takeIf { it in profiles }
                    ?: (if (!previousProfile.isNullOrBlank()) "custom" else null)
                    ?: legacyDefaults.entries.firstOrNull { it.value == current.trimEnd('/') }?.key
                    ?: if (current.isEmpty()) "syncclipboard" else "custom"
            editor.putString(SERVER_PROFILE_TYPE_KEY, profile)
            if (current.isEmpty()) {
                // Preserve only the previously selected implicit endpoint. Unconfigured
                // alternatives must not silently contact an old hard-coded LAN machine.
                val selected =
                    migrated["clip_sync_server_address_$profile"].orEmpty().ifEmpty { legacyDefaults[profile].orEmpty() }
                if (selected.isNotEmpty()) {
                    editor.putString(SERVER_ADDRESS_KEY, selected)
                    editor.putString("clip_sync_server_address_$profile", selected)
                }
            }
        }
        editor.putInt(VERSION, 1).apply()
    }

    fun hasConfiguredCurrent(prefs: SharedPreferences): Boolean {
        val current = runCatching { prefs.getString(SERVER_ADDRESS_KEY, "").orEmpty() }.getOrDefault("")
        val profile = runCatching { prefs.getString(SERVER_PROFILE_TYPE_KEY, "syncclipboard") }.getOrNull()
        val address =
            current.ifBlank {
                if (profile in profiles) {
                    runCatching { prefs.getString("clip_sync_server_address_$profile", "").orEmpty() }.getOrDefault("")
                } else {
                    ""
                }
            }
        return address.isNotBlank() && runCatching { SyncServerAddress.normalize(address) }.isSuccess
    }
}
