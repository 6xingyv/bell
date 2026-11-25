// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.playback

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Seekable HTTP bytes, with bounded LRU storage and cancellable sequential read-ahead. */
internal class AudioRangeReader(
    private val client: HttpClient,
    private val url: String,
    private val length: Long,
    private val blockSize: Int = 256 * 1024,
    private val cacheBlocks: Int = 32,
    private val aheadBlocks: Int = 8,
    private val maxAheadBlocks: Int = 32,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private val aheadLock = Mutex()
    // Keep one request slot available for a foreground cache miss while read-ahead is active.
    private val requests = Semaphore(4)
    private val cache = LinkedHashMap<Long, ByteArray>()
    @Volatile private var readAheadBlocks = aheadBlocks
    private var fastBlocks = 0
    private var lastAheadCursor = -1L

    private class Pending(val task: Deferred<ByteArray>, var speculative: Boolean)

    private val pending = mutableMapOf<Long, Pending>()
    private val ahead = Channel<Long>(Channel.CONFLATED)

    init {
        require(
            length >= 0 &&
                blockSize > 0 &&
                cacheBlocks > 0 &&
                aheadBlocks >= 0 &&
                maxAheadBlocks >= aheadBlocks
        )
        scope.launch {
            ahead.receiveAsFlow().collectLatest { cursor ->
                // A seek abandons speculative requests for the old window.
                lock.withLock {
                    pending
                        .filter { (index, entry) ->
                            entry.speculative &&
                                (index <= cursor || index > cursor + readAheadBlocks)
                        }
                        .values
                        .forEach { it.task.cancel() }
                }
                val window = readAheadBlocks
                for (delta in 1..window) {
                    val index = cursor + delta
                    if (index * blockSize >= length) break
                    try {
                        block(index, speculative = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }

    private suspend fun block(index: Long, speculative: Boolean = false): ByteArray {
        scope.coroutineContext.ensureActive()
        val task = lock.withLock {
            cache.remove(index)?.let {
                cache[index] = it
                return it
            }
            pending[index]
                ?.takeUnless { it.task.isCancelled }
                ?.also { if (!speculative) it.speculative = false }
                ?.task
                ?: scope
                    .async(start = CoroutineStart.LAZY) {
                        val task = currentCoroutineContext()[Job]
                        val startedAt = TimeSource.Monotonic.markNow()
                        try {
                            val start = index * blockSize
                            val end = minOf(length - 1, start + blockSize - 1)
                            val bytes = requests.withPermit {
                                retryAudioRead {
                                    client
                                        .prepareGet(url) {
                                            timeout {
                                                requestTimeoutMillis = 8_000
                                                socketTimeoutMillis = 5_000
                                                connectTimeoutMillis = 5_000
                                            }
                                            header(HttpHeaders.Range, "bytes=$start-$end")
                                            header(HttpHeaders.AcceptEncoding, "identity")
                                        }
                                        .execute { response ->
                                            check(
                                                response.status == HttpStatusCode.PartialContent
                                            ) {
                                                "Server does not support byte ranges"
                                            }
                                            check(
                                                response.headers[HttpHeaders.ContentRange] ==
                                                    "bytes $start-$end/$length"
                                            ) {
                                                "Invalid audio range"
                                            }
                                            response.readRawBytes().also {
                                                check(it.size.toLong() == end - start + 1) {
                                                    "Incomplete audio range"
                                                }
                                            }
                                        }
                                }
                            }
                            ensureActive()
                            lock.withLock {
                                cache.remove(index)
                                cache[index] = bytes
                                while (cache.size > cacheBlocks) cache.remove(cache.keys.first())
                                if (startedAt.elapsedNow().inWholeMilliseconds >= 250) {
                                    readAheadBlocks =
                                        (readAheadBlocks + 2).coerceAtMost(maxAheadBlocks)
                                    fastBlocks = 0
                                } else if (++fastBlocks >= 12) {
                                    readAheadBlocks =
                                        (readAheadBlocks - 1).coerceAtLeast(aheadBlocks)
                                    fastBlocks = 0
                                }
                            }
                            bytes
                        } finally {
                            withContext(NonCancellable) {
                                lock.withLock {
                                    if (pending[index]?.task === task) pending.remove(index)
                                }
                            }
                        }
                    }
                    .also { pending[index] = Pending(it, speculative) }
        }
        task.start()
        return task.await()
    }

    suspend fun read(offset: Long, size: Int, data: ByteArray, dataOffset: Int = 0): Int {
        scope.coroutineContext.ensureActive()
        require(offset >= 0 && size >= 0 && dataOffset >= 0 && size <= data.size - dataOffset)
        if (offset >= length || size == 0) return 0
        var copied = 0
        val count = minOf(size.toLong(), length - offset).toInt()
        while (copied < count) {
            val position = offset + copied
            val bytes = block(position / blockSize)
            val within = (position % blockSize).toInt()
            val take = minOf(count - copied, bytes.size - within)
            check(take > 0)
            bytes.copyInto(data, dataOffset + copied, within, within + take)
            copied += take
        }
        val cursor = (offset + copied - 1) / blockSize
        aheadLock.withLock {
            if (cursor != lastAheadCursor) {
                lastAheadCursor = cursor
                ahead.trySend(cursor)
            }
        }
        return copied
    }

    fun close() {
        ahead.close()
        scope.cancel()
    }
}

private suspend fun <T> retryAudioRead(block: suspend () -> T): T {
    var failure: Exception? = null
    repeat(3) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            if (attempt < 2) delay(100L shl attempt)
        }
    }
    throw requireNotNull(failure)
}
