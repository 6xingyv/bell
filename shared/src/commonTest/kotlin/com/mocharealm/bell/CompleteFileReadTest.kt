// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.playback.readCompleteFileRange
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class CompleteFileReadTest {
    @Test
    fun fillsRequestAcrossPartialCacheAndNetworkReads() = runBlocking {
        val expected = ByteArray(131072) { (it % 251 + 1).toByte() }
        val actual = ByteArray(expected.size)
        var calls = 0
        val read =
            readCompleteFileRange(0, actual.size, expected.size.toLong()) {
                position,
                remaining,
                destination ->
                val count = minOf(remaining, if (calls++ == 0) 3000 else 65536)
                expected.copyInto(actual, destination, position.toInt(), position.toInt() + count)
                count
            }
        assertEquals(expected.size, read)
        assertContentEquals(expected, actual)
        assertEquals(3, calls)
    }

    @Test
    fun onlyTrueEofAllowsShortResponse() = runBlocking {
        assertEquals(7, readCompleteFileRange(93, 4096, 100) { _, count, _ -> count })
        assertEquals(
            0,
            readCompleteFileRange(100, 4096, 100) { _, _, _ -> error("EOF must not read") },
        )
        assertFailsWith<IllegalStateException> {
            readCompleteFileRange(0, 4096, 100) { _, _, _ -> 0 }
        }
        Unit
    }
}
