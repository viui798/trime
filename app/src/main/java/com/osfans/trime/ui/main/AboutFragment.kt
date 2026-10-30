/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.formatDateTime

class AboutFragment : PaddingPreferenceFragment() {

    @SuppressLint("UseKtx")
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // 品牌区：一行应用名 + 一句定位，不可点击
            addPreference(
                Preference(requireContext()).apply {
                    isIconSpaceReserved = false
                    isSelectable = false
                    isSingleLineTitle = false
                    setTitle(R.string.app_name_release)
                    summary = getString(R.string.about_intro)
                },
            )
            // 用 Android 标准的 versionName（1.2.7-cheese），别用 git describe 的
            // v1.2.6-cheese-12-gxxxx，那个和设置里显示的版本号对不上，容易让人困惑。
            addPreference(R.string.current_version, BuildConfig.VERSION_NAME) {
                openUrl("${Const.ZHI_REPO_URL}/commit/${BuildConfig.BUILD_COMMIT_HASH}")
            }

            addCategory(R.string.about_category_project) {
                isIconSpaceReserved = false
                addPreference(R.string.about_project_page, getString(R.string.about_project_page_summary)) {
                    openUrl(Const.ZHI_REPO_URL)
                }
                addPreference(R.string.about_latest_release, getString(R.string.about_latest_release_summary)) {
                    openUrl(Const.ZHI_LATEST_RELEASE_URL)
                }
                addPreference(R.string.about_all_releases, getString(R.string.about_all_releases_summary)) {
                    openUrl(Const.ZHI_RELEASES_URL)
                }
            }

            addCategory(R.string.about_category_engine) {
                isIconSpaceReserved = false
                addPreference(R.string.librime_version, BuildConfig.LIBRIME_VERSION) {
                    val hash = getCommitFromVersionName(BuildConfig.LIBRIME_VERSION)
                    openUrl("${Const.LIBRIME_URL}/commit/$hash")
                }
                addPreference(R.string.opencc_version, BuildConfig.OPENCC_VERSION) {
                    val hash = getCommitFromVersionName(BuildConfig.OPENCC_VERSION)
                    openUrl("${Const.OPENCC_URL}/commit/$hash")
                }
                addPreference(
                    Preference(requireContext()).apply {
                        isIconSpaceReserved = false
                        isCopyingEnabled = true
                        setTitle(R.string.build_info)
                        summary = requireContext().getString(
                            R.string.build_info_format,
                            BuildConfig.BUILDER,
                            BuildConfig.BUILD_COMMIT_HASH,
                            formatDateTime(BuildConfig.BUILD_TIMESTAMP),
                        )
                    },
                )
            }

            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.privacy_policy) {
                    openUrl(Const.PRIVACY_POLICY_URL)
                }
                addPreference(R.string.source_code, R.string.git_repo) {
                    openUrl(Const.ZHI_REPO_URL)
                }
                addPreference(R.string.upstream_project, R.string.upstream_project_summary) {
                    openUrl(Const.UPSTREAM_REPO_URL)
                }
                addPreference(R.string.license, Const.LICENSE_SPDX_ID) {
                    openUrl(Const.LICENSE_URL)
                }
                addPreference(
                    R.string.open_source_licenses,
                    R.string.licenses_of_third_party_libraries,
                ) {
                    findNavController().navigate(NavigationRoute.License)
                }
            }
        }
    }

    /** 打开外部链接；设备上没有浏览器时给一句提示，别让点击像没反应。 */
    private fun openUrl(url: String) {
        val handled = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!handled) {
            Toast.makeText(requireContext(), R.string.about_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val DASH_G_PATTERN = Regex("^(.*-g)([0-9a-f]+)(.*)$")
        private val COMMON_PATTERN = Regex("^([^-]*)(-.*)$")

        private fun getCommitFromVersionName(versionCode: String): String {
            val dashG = DASH_G_PATTERN.find(versionCode)?.groupValues?.get(2)
            val common = COMMON_PATTERN.find(versionCode)?.groupValues?.get(1)
            return dashG ?: common ?: versionCode
        }
    }
}
