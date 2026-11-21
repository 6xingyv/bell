// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.playback.mediaHash
import com.mocharealm.bell.domain.repository.PlaybackSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*

class StreamingAudioCacheTest {
    private val data = ByteArray(32768) { (it % 251).toByte() }

    private fun directory() =
        Files.createTempDirectory(
                File(System.getProperty("jellify.cacheDir", "D:/BellBuildCache"))
                    .apply { mkdirs() }
                    .toPath(),
                "stream-test-",
            )
            .toFile()

    @Test
    fun completePrefetchIsReusableAfterProcessRestartWithoutNetwork() = runBlocking {
        var requests = 0
        val client =
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        data,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, data.size.toString()),
                    )
                }
            )
        val root = directory()
        val cache = StreamingAudioCache(root, client)
        val source = PlaybackSource("http://cache.test/audio?api_key=fixture", "account/song")
        try {
            val file = cache.entry(source).awaitComplete()
            assertContentEquals(data, file.readBytes())
            assertEquals(1, requests)
            val restarted = StreamingAudioCache(root, client)
            try {
                assertContentEquals(
                    data,
                    restarted
                        .entry(source.copy(url = "http://cache.test/audio?api_key=renewed"))
                        .awaitComplete()
                        .readBytes(),
                )
                assertEquals(1, requests)
            } finally {
                restarted.close()
            }
            assertTrue(root.listFiles()!!.none { it.readText().contains("api_key") })
        } finally {
            cache.close()
            client.close()
        }
    }

    @Test
    fun interruptedStreamResumesAndItsPrefixIsReadableBeforeCompletion() = runBlocking {
        var calls = 0
        var resumed: String? = null
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    if (calls == 1) {
                        val channel = ByteChannel(autoFlush = true)
                        channel.writeFully(data, 0, 1024)
                        respond(
                            channel,
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ContentLength to listOf(data.size.toString()),
                                HttpHeaders.ETag to listOf("fixture-v1"),
                            ),
                        )
                    } else {
                        resumed = request.headers[HttpHeaders.Range]
                        assertEquals("fixture-v1", request.headers[HttpHeaders.IfRange])
                        respond(
                            data.copyOfRange(1024, data.size),
                            HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ContentRange to
                                    listOf("bytes 1024-${data.lastIndex}/${data.size}"),
                                HttpHeaders.ETag to listOf("fixture-v1"),
                            ),
                        )
                    }
                }
            )
        val root = directory()
        val source = PlaybackSource("http://cache.test/stream", "account/song")
        val first = StreamingAudioCache(root, client)
        try {
            val entry = first.entry(source)
            val worker = entry.start()
            val prefix = ByteArray(1024)
            withTimeout(3000) { while (entry.prefixRead(0, 1024, prefix) != 1024) delay(5) }
            assertContentEquals(data.copyOfRange(0, 1024), prefix)
            assertFalse(worker.isCompleted)
            first.close()
            runCatching { worker.await() }
            val second = StreamingAudioCache(root, client)
            try {
                assertContentEquals(data, second.entry(source).awaitComplete().readBytes())
                assertEquals("bytes=1024-", resumed)
            } finally {
                second.close()
            }
        } finally {
            first.close()
            client.close()
        }
    }

    @Test
    fun completePartialFileIsPromotedAfterACrashWithoutFetchingAgain() = runBlocking {
        val root = directory()
        val key = mediaHash("account/song")
        File(root, "$key.part").writeBytes(data)
        File(root, "$key.json").writeText("{\"length\":${data.size}}")
        val client =
            HttpClient(MockEngine { error("Complete prefix must not be downloaded again") })
        val cache = StreamingAudioCache(root, client)
        try {
            assertContentEquals(
                data,
                cache
                    .entry(PlaybackSource("http://cache.test/audio", "account/song"))
                    .awaitComplete()
                    .readBytes(),
            )
        } finally {
            cache.close()
            client.close()
        }
    }

    @Test
    fun cachedReadsAppendWithoutOverwritingEarlierBytes() = runBlocking {
        val root = directory()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        data,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, data.size.toString()),
                    )
                }
            )
        val cache = StreamingAudioCache(root, client)
        try {
            val entry = cache.entry(PlaybackSource("http://cache.test/audio", "account/offset"))
            entry.awaitComplete()
            val output = ByteArray(110) { 99 }
            assertEquals(100, entry.prefixRead(23, 100, output, 10))
            assertContentEquals(ByteArray(10) { 99 }, output.copyOfRange(0, 10))
            assertContentEquals(data.copyOfRange(23, 123), output.copyOfRange(10, 110))
        } finally {
            cache.close()
            client.close()
        }
    }
}
