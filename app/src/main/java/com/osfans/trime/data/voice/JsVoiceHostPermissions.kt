/* SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported to Trime for the Zhi Trime project.
 */
package com.osfans.trime.data.voice

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.util.appContext
import okhttp3.HttpUrl

/**
 * 插件自己声明的 hosts 只是上限，永远不构成用户的同意。
 *
 * 授权按 provider id 记在独立的 SharedPreferences 里：内置与导入插件同一条规则，
 * 不自动批准（升级版本的老插件也要求重新确认），移除插件即撤销。
 */
internal object JsVoiceHostPermissions {
    private const val PREFS = "js_voice_host_permissions"

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun approvedHosts(pluginId: String): Set<String> =
        prefs().getStringSet(pluginId, emptySet()).orEmpty().toSet()

    fun isApproved(pluginId: String, hosts: Set<String>): Boolean =
        hosts.isNotEmpty() && approvedHosts(pluginId).containsAll(hosts)

    /** 只在用户确认过界面上展示的完整主机列表后调用。 */
    fun approve(pluginId: String, hosts: Set<String>) {
        require(hosts.isNotEmpty() && hosts.all { JsVoicePluginMeta.normalizeHost(it) == it })
        check(prefs().edit().putStringSet(pluginId, hosts.toSet()).commit())
    }

    fun revoke(pluginId: String) {
        check(prefs().edit().remove(pluginId).commit())
    }
}

/** 每次网络操作（含已建立连接上的 send）都重读授权，而不是在会话开始时记住一次。 */
internal class JsVoiceNetworkPolicy(
    private val pluginId: String,
    private val declaredHosts: Set<String>,
) {
    fun requireApproval() {
        check(JsVoiceHostPermissions.isApproved(pluginId, declaredHosts)) {
            appContext.getString(R.string.voice_network_not_approved)
        }
    }

    fun checkUrl(url: String): HttpUrl {
        val parsed = JsVoiceHelpers.secureUrl(url)
        require(parsed != null && parsed.host.trimEnd('.') in declaredHosts) {
            "Host is not declared by the plugin"
        }
        requireApproval()
        return parsed
    }
}
