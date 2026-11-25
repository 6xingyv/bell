// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.playback

/** FUSE replies must fill the request before EOF; a short reply is zero-padded by the kernel. */
internal suspend fun readCompleteFileRange(
    offset: Long,
    size: Int,
    length: Long,
    readPart: suspend (Long, Int, Int) -> Int,
): Int {
    require(offset >= 0 && size >= 0)
    val target = minOf(size.toLong(), (length - offset).coerceAtLeast(0)).toInt()
    var copied = 0
    while (copied < target) {
        val count = readPart(offset + copied, target - copied, copied)
        check(count in 1..(target - copied)) { "Incomplete file read before EOF" }
        copied += count
    }
    return copied
}
