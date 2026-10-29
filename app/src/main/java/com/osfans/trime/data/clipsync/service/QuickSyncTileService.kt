/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/service/QuickSyncTileService.kt。
 */
package com.osfans.trime.data.clipsync.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import com.osfans.trime.R
import com.osfans.trime.data.clipsync.ClipSyncService
import com.osfans.trime.data.clipsync.SyncServerPreferences

@SuppressLint("NewApi")
class QuickSyncTileService : TileService() {
    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this).also(SyncServerPreferences::migrate)
    }
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ClipSyncService.PREF_QUICK_SYNC) {
                refreshTile()
            }
        }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshTile()
    }

    override fun onStopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    /**
     * Toggle quick sync, starting or stopping the service to match.
     *
     * Flipping the preference alone was not enough: the service's pref listener only reacts while
     * the service is already alive, so turning sync on from the tile left it ACTIVE with nothing
     * running until the IME was next opened (see C20).
     */
    override fun onClick() {
        super.onClick()
        val enabled = prefs.getBoolean(ClipSyncService.PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        val newValue = !enabled
        prefs
            .edit()
            .putBoolean(ClipSyncService.PREF_QUICK_SYNC, newValue)
            .putBoolean(ClipSyncService.PREF_QUICK_SYNC_UNREACHABLE, false)
            .apply()
        if (newValue) {
            // forceEnableSync: the preference write above may not be visible to the service yet.
            ClipSyncService.startSyncService(this, "qs-tile", forceEnableSync = true)
        } else {
            ClipSyncService.stopSyncService(this)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = prefs.getBoolean(ClipSyncService.PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        tile.label = getString(R.string.clip_sync_tile_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        private const val DEFAULT_QUICK_SYNC_ENABLED = false

        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            requestListeningState(
                context,
                ComponentName(context, QuickSyncTileService::class.java),
            )
        }
    }
}
