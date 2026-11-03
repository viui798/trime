/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.prefs

/** Temporary rendering values. Never persisted: process death cancels an unconfirmed preview. */
object KeyboardSizePreview {
    @Volatile var heightDp: Int? = null
    @Volatile var safeAreaDp: Int? = null

    fun height(): Int = heightDp ?: AppPrefs.defaultInstance().keyboard.heightOverride.getValue()
    fun safeArea(): Int = safeAreaDp ?: AppPrefs.defaultInstance().keyboard.bottomSafeArea.getValue()
}
