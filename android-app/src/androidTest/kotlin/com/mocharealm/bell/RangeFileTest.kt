// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.platform.RangeFile
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.Assert.*
import org.junit.Test

class RangeFileTest {
    @Test
    fun randomReadsAreExactAndCacheEvictsAfterEightMiB() {
        val block = 256 * 1024
        val length = 40L * block + 13
        var requests = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    val pair =
                        request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-')
                    val first = pair[0].toLong()
                    val last = pair[1].toLong()
                    assertTrue(last - first + 1 <= block)
                    requests++
                    respond(
                        ByteArray((last - first + 1).toInt()) { ((first + it) % 251).toByte() },
                        HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes $first-$last/$length"),
                    )
                }
            )
        val file = RangeFile(client, "http://localhost/audio", length)
        fun read(offset: Long, count: Int) {
            val bytes = ByteArray(count)
            assertEquals(count, file.onRead(offset, count, bytes))
            assertArrayEquals(ByteArray(count) { ((offset + it) % 251).toByte() }, bytes)
        }
        try {
            read(0, 10)
            read(0, 10)
            assertEquals(1, requests)
            read(block - 3L, 10)
            assertEquals(2, requests)
            read(length - 13, 13)
            assertEquals(3, requests)
            for (index in 2..33) read(index.toLong() * block + 7, 10)
            val before = requests
            read(0, 10)
            assertEquals(
                "Old blocks must be evicted instead of retaining an entire song",
                before + 1,
                requests,
            )
            assertEquals(0, file.onRead(length, 100, ByteArray(100)))
        } finally {
            file.onRelease()
            client.close()
        }
    }
}
