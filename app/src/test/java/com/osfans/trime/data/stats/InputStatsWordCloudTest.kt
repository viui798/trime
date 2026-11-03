// SPDX-FileCopyrightText: 2026 Zhi Trime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.stats

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InputStatsWordCloudTest :
    BehaviorSpec({
        given("a librime userdb TSV export") {
            `when`("entries carry commit counts") {
                then("the phrase and c= value are extracted") {
                    InputStats.parseUserDbTsv(
                        "abc \t你好\tc=3 d=0.5 t=123\n" +
                            "def \t世界\tc=1 d=0 t=456\n" +
                            "not-a-row\n",
                    ) shouldBe mapOf("你好" to 3L, "世界" to 1L)
                }
            }
        }
    })
