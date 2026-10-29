/* SPDX-License-Identifier: LGPL-2.1-or-later */
/*
 * 移植自中州鹅 clipboardsync/ClipboardEcho.kt。
 */
package com.osfans.trime.data.clipsync

/** Match both newly normalized state and raw state persisted before host filtering existed. */
internal fun isClipboardEcho(
    raw: String,
    normalized: String,
    remote: String?,
    uploaded: String?,
    normalize: (String) -> String,
): Boolean =
    sequenceOf(remote, uploaded).filterNotNull().any {
        it == raw || it == normalized || normalize(it) == normalized
    }
