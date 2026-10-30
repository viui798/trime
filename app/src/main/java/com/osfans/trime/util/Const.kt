/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import com.osfans.trime.BuildConfig

object Const {
    const val VERSION_NAME = "${BuildConfig.BUILD_VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    const val LICENSE_SPDX_ID = "GPL-3.0-or-later"
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    /** 芝版同文自己的仓库，拼链接时要的是不带 `.git` 的地址。 */
    const val ZHI_REPO_URL = "https://github.com/viui798/trime"
    const val ZHI_RELEASES_URL = "$ZHI_REPO_URL/releases"
    const val ZHI_LATEST_RELEASE_URL = "$ZHI_REPO_URL/releases/latest"

    /** 上游同文（GPL 来源）。 */
    const val UPSTREAM_REPO_URL = "https://github.com/osfans/trime"

    const val PRIVACY_POLICY_URL = "https://github.com/osfans/trime/blob/develop/PRIVACY.md"
    const val LIBRIME_URL = "https://github.com/rime/librime"
    const val OPENCC_URL = "https://github.com/BYVoid/OpenCC"
    const val QQ_GROUP_1_NUM = "811142286"
    const val QQ_GROUP_2_NUM = "224230445"
    const val RIME_QQ_GROUP_NUM = "77608640"
    const val TELEGRAM_NAME = "@trime_dev"
    const val QQ_GROUP_1_URL = "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26k%3DzifdI0iaEYjATwYkvVzP1cjsUEWSa9Ha"
    const val QQ_GROUP_2_URL = "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26k%3DtZzftXfwDX6JAFaq3OysVW3BcPHUSvFV"
    const val RIME_QQ_GROUP_URL = "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26k%3Dm505aYSeLZjOwWeMNAViWylZZDYqMrxV"
    const val TELEGRAM_URL = "https://t.me/trime_dev"
}
