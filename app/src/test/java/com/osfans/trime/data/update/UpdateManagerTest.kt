// SPDX-FileCopyrightText: 2026 Zhi Trime
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.update

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class UpdateManagerTest :
    BehaviorSpec({
        given("version comparison") {
            `when`("the remote tag is newer") {
                then("it is an update") {
                    UpdateManager.isNewer("v1.2.14-cheese", "1.2.13-cheese") shouldBe true
                    UpdateManager.isNewer("v1.3.0-cheese", "1.2.99-cheese") shouldBe true
                }
            }
            `when`("the remote tag is the same or older") {
                then("it is not an update") {
                    UpdateManager.isNewer("v1.2.13-cheese", "1.2.13-cheese") shouldBe false
                    UpdateManager.isNewer("v1.2.12-cheese", "1.2.13-cheese") shouldBe false
                }
            }
            `when`("the remote tag has no version") {
                then("it is not treated as an update") {
                    UpdateManager.isNewer("latest", "1.2.13-cheese") shouldBe false
                }
            }
        }

        given("checksum parsing") {
            `when`("the file contains valid lines") {
                then("hashes are mapped to file names") {
                    val hash = "a".repeat(64)
                    UpdateManager.parseChecksums(
                        "$hash  trime-cheese-arm64-v8a.apk\n" +
                            "bad line\n",
                    ) shouldBe mapOf("trime-cheese-arm64-v8a.apk" to hash)
                }
            }
        }

        given("asset selection") {
            val release =
                GitHubRelease(
                    tag = "v1.2.14-cheese",
                    assets =
                        listOf(
                            GitHubAsset("trime-cheese-arm64-v8a.apk", "https://example/arm64", 1),
                            GitHubAsset("trime-cheese-x86_64.apk", "https://example/x86_64", 1),
                        ),
                )
            `when`("the device ABI matches an asset") {
                then("that asset is selected") {
                    UpdateManager.pickAsset(release, "x86_64")?.name shouldBe "trime-cheese-x86_64.apk"
                }
            }
            `when`("the device ABI has no asset") {
                then("nothing is selected") {
                    UpdateManager.pickAsset(release, "armeabi-v7a") shouldBe null
                }
            }
        }
    })
