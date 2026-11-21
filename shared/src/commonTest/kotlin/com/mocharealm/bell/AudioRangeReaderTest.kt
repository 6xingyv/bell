// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.playback.AudioRangeReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*

class AudioRangeReaderTest {
    private val bytes = ByteArray(4096) { (it % 251).toByte() }

    private fun client(
        requests: MutableList<Long>,
        stall: suspend (Long) -> Unit = {},
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                val range =
                    Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.headers[HttpHeaders.Range]!!)!!
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                requests += start.toLong()
                stall(start.toLong())
                respond(
                    bytes.copyOfRange(start, end + 1),
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${bytes.size}"),
                )
            }
        )

    @Test
    fun crossBlockReadsBackwardSeeksAndEofReturnExactBytes() = runBlocking {
        val requests = mutableListOf<Long>()
        val client = client(requests)
        val reader =
            AudioRangeReader(
                client,
                "http://audio.test/stream",
                bytes.size.toLong(),
                blockSize = 128,
                cacheBlocks = 2,
                aheadBlocks = 0,
                dispatcher = Dispatchers.Default,
            )
        try {
            val result = ByteArray(300)
            assertEquals(300, reader.read(90, 300, result))
            assertContentEquals(bytes.copyOfRange(90, 390), result)
            val seek = ByteArray(45)
            assertEquals(45, reader.read(3, 45, seek))
            assertContentEquals(bytes.copyOfRange(3, 48), seek)
            val tail = ByteArray(100)
            assertEquals(11, reader.read(4085, 100, tail))
            assertContentEquals(bytes.copyOfRange(4085, 4096), tail.copyOf(11))
            assertEquals(0, reader.read(4096, 100, tail))
        } finally {
            reader.close()
            client.close()
        }
    }

    @Test
    fun readAheadFillsOnlyABoundedWindowAndNextReadUsesIt() = runBlocking {
        val requests = mutableListOf<Long>()
        val prefetched = CompletableDeferred<Unit>()
        val client = client(requests) { if (it == 256L) prefetched.complete(Unit) }
        val reader =
            AudioRangeReader(
                client,
                "http://audio.test/stream",
                bytes.size.toLong(),
                blockSize = 128,
                cacheBlocks = 4,
                aheadBlocks = 2,
                dispatcher = Dispatchers.Default,
            )
        try {
            reader.read(0, 30, ByteArray(30))
            withTimeout(1000) { prefetched.await() }
            assertEquals(listOf(0L, 128L, 256L), requests)
            val result = ByteArray(40)
            reader.read(150, 40, result)
            assertContentEquals(bytes.copyOfRange(150, 190), result)
            assertEquals(1, requests.count { it == 128L })
        } finally {
            reader.close()
            client.close()
        }
    }

    @Test
    fun aStalledSpeculativeReadDoesNotBlockASeek() = runBlocking {
        val requests = mutableListOf<Long>()
        val stalled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val client =
            client(requests) {
                if (it == 128L) {
                    started.complete(Unit)
                    stalled.await()
                }
            }
        val reader =
            AudioRangeReader(
                client,
                "http://audio.test/stream",
                bytes.size.toLong(),
                blockSize = 128,
                cacheBlocks = 4,
                aheadBlocks = 2,
                dispatcher = Dispatchers.Default,
            )
        try {
            reader.read(0, 20, ByteArray(20))
            withTimeout(1000) { started.await() }
            assertTrue(128L in requests)
            val result = ByteArray(50)
            withTimeout(1000) { reader.read(1000, 50, result) }
            assertContentEquals(bytes.copyOfRange(1000, 1050), result)
        } finally {
            reader.close()
            client.close()
        }
    }

    @Test
    fun transientRangeFailureRetriesWithoutReturningAnEof() = runBlocking {
        var calls = 0
        val client =
            HttpClient(
                MockEngine {
                    calls++
                    if (calls == 1) respond("busy", HttpStatusCode.ServiceUnavailable)
                    else
                        respond(
                            bytes.copyOfRange(0, 128),
                            HttpStatusCode.PartialContent,
                            headersOf(HttpHeaders.ContentRange, "bytes 0-127/${bytes.size}"),
                        )
                }
            )
        val reader =
            AudioRangeReader(client, "http://audio.test/stream", bytes.size.toLong(), 128, 2, 0)
        try {
            val output = ByteArray(64)
            assertEquals(64, reader.read(0, 64, output))
            assertContentEquals(bytes.copyOfRange(0, 64), output)
            assertEquals(2, calls)
        } finally {
            reader.close()
            client.close()
        }
    }
}
