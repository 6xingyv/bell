// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.playback

import com.mocharealm.bell.data.network.wireJson
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.repository.PlaybackSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

internal fun mediaHash(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

internal fun mediaScope(session: Session) =
    mediaHash(session.server.trimEnd('/').lowercase() + "/" + session.user.id)

internal fun atomicMediaText(file: File, text: String) {
    file.parentFile.mkdirs()
    val pending = File(file.parentFile, file.name + ".new")
    pending.writeText(text)
    try {
        java.nio.file.Files.move(
            pending.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(
            pending.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

private class RetryableAudioCacheException(message: String) : IOException(message)

private fun Throwable.isRetryableAudioTransfer(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is IOException || cause::class.simpleName?.contains("Timeout") == true)
            return true
        cause = cause.cause
    }
    return false
}

/**
 * Complete tracks are streamed to disk; native playback can read the available prefix immediately.
 */
class StreamingAudioCache(
    private val root: File,
    private val client: HttpClient,
    private val maximumBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var currentKey: String? = null

    @Serializable private data class Info(val length: Long = 0, val etag: String? = null)

    init {
        root.mkdirs()
    }

    fun entry(source: PlaybackSource): Entry =
        entries
            .computeIfAbsent(mediaHash(source.cacheKey)) { Entry(it, source.url) }
            .also { it.url = source.url }

    fun completed(source: PlaybackSource): File? =
        File(root, mediaHash(source.cacheKey) + ".audio")
            .takeIf { it.isFile && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }

    fun prioritize(source: PlaybackSource) {
        val keep = mediaHash(source.cacheKey)
        currentKey = keep
        entries.forEach { (key, entry) -> if (key != keep) entry.cancelSpeculation() }
    }

    fun prefetch(source: PlaybackSource) {
        entry(source).start()
    }

    fun close() {
        scope.cancel()
    }

    inner class Entry
    internal constructor(private val key: String, @Volatile internal var url: String) {
        private val complete = File(root, "$key.audio")
        private val partial = File(root, "$key.part")
        private val metadata = File(root, "$key.json")
        @Volatile
        private var info = runCatching {
            wireJson.decodeFromString<Info>(metadata.readText())
        }
            .getOrDefault(Info())
        @Volatile private var prefix = partial.length()
        @Volatile private var worker: Deferred<File>? = null
        @Volatile private var pins = 0

        @Synchronized
        fun start(): Deferred<File> {
            if (complete.isFile && complete.length() > 0) return CompletableDeferred(complete)
            val preceding = worker
            return preceding?.takeIf { it.isActive }
                ?: scope
                    .async {
                        preceding?.join()
                        slots.withPermit { transfer() }
                    }
                    .also { worker = it }
        }

        suspend fun length(): Long {
            if (complete.isFile) return complete.length()
            if (info.length > 0) return info.length
            withTimeoutOrNull(150) {
                while (info.length == 0L && worker?.isActive == true) delay(10)
            }
            if (info.length > 0) return info.length
            val size =
                client
                    .prepareGet(url) {
                        timeout {
                            requestTimeoutMillis = 5_000
                            connectTimeoutMillis = 3_000
                        }
                        header(HttpHeaders.Range, "bytes=0-0")
                        header(HttpHeaders.AcceptEncoding, "identity")
                    }
                    .execute { response ->
                        check(response.status == HttpStatusCode.PartialContent) {
                            "Server does not support seekable audio"
                        }
                        response.headers[HttpHeaders.ContentRange]
                            ?.substringAfterLast('/')
                            ?.toLongOrNull() ?: error("Missing audio length")
                    }
            info = info.copy(length = size)
            return size
        }

        suspend fun awaitComplete(): File {
            synchronized(this) { pins++ }
            return try {
                start().await()
            } finally {
                synchronized(this) { pins-- }
            }
        }

        fun downloadFraction(): Float =
            if (complete.isFile) 1f
            else if (info.length > 0) (prefix.toDouble() / info.length).toFloat().coerceIn(0f, 1f)
            else 0f

        fun prefixRead(offset: Long, count: Int, data: ByteArray, dataOffset: Int = 0): Int {
            // Promotion can rename the prefix between choosing its path and opening it.
            // A cache miss must fall through to HTTP, never become a native decoder error.
            for (file in listOf(complete, partial, complete)) {
                try {
                    return RandomAccessFile(file, "r").use { input ->
                        val available =
                            if (file == complete) input.length() else minOf(prefix, input.length())
                        val take =
                            minOf(count.toLong(), (available - offset).coerceAtLeast(0)).toInt()
                        if (take == 0) return@use 0
                        input.seek(offset)
                        input.read(data, dataOffset, take).coerceAtLeast(0)
                    }
                } catch (_: IOException) {}
            }
            return 0
        }

        suspend fun awaitPrefix(
            offset: Long,
            count: Int,
            data: ByteArray,
            dataOffset: Int = 0,
        ): Int {
            if (offset > prefix + 128 * 1024 || worker?.isActive != true) return 0
            return withTimeoutOrNull(120) {
                var read = prefixRead(offset, count, data, dataOffset)
                while (read == 0 && worker?.isActive == true) {
                    delay(8)
                    read = prefixRead(offset, count, data, dataOffset)
                }
                read
            } ?: 0
        }

        private suspend fun transfer(): File {
            var attempt = 0
            while (true) {
                try {
                    return transferOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!e.isRetryableAudioTransfer()) throw e
                    val backoff = (250L shl attempt.coerceAtMost(7)).coerceAtMost(30_000L)
                    delay(backoff)
                    attempt++
                }
            }
        }

        private suspend fun transferOnce(): File {
            val offset = partial.length()
            if (info.length > 0 && offset == info.length) {
                java.nio.file.Files.move(
                    partial.toPath(),
                    complete.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                trim(key)
                return complete
            }
            client
                .prepareGet(url) {
                    // A whole lossless track can legitimately take longer than API requests.
                    timeout {
                        requestTimeoutMillis = Long.MAX_VALUE
                        socketTimeoutMillis = 60_000
                    }
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (offset > 0) {
                        header(HttpHeaders.Range, "bytes=$offset-")
                        info.etag?.let { header(HttpHeaders.IfRange, it) }
                    }
                }
                .execute { response ->
                    if (
                        response.status.value == 408 ||
                            response.status.value == 429 ||
                            response.status.value >= 500
                    )
                        throw RetryableAudioCacheException(
                            "Audio prefetch temporarily failed (${response.status.value})"
                        )
                    check(
                        response.status == HttpStatusCode.OK ||
                            response.status == HttpStatusCode.PartialContent
                    ) {
                        "Audio prefetch failed (${response.status.value})"
                    }
                    val append = offset > 0 && response.status == HttpStatusCode.PartialContent
                    if (append)
                        check(
                            response.headers[HttpHeaders.ContentRange]?.startsWith(
                                "bytes $offset-"
                            ) == true
                        ) {
                            "Invalid resumed audio range"
                        }
                    val expected =
                        if (append)
                            response.headers[HttpHeaders.ContentRange]
                                ?.substringAfterLast('/')
                                ?.toLongOrNull()
                        else response.contentLength()
                    info = Info(expected ?: 0, response.headers[HttpHeaders.ETag])
                    atomicMediaText(metadata, wireJson.encodeToString(info))
                    prefix = if (append) offset else 0
                    partial.outputStreamForAppend(append).use { output ->
                        val input = response.bodyAsChannel()
                        val bytes = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.readAvailable(bytes)
                            if (size < 0) break
                            if (size > 0) {
                                check(root.usableSpace > 32L * 1024 * 1024) { "存储空间不足" }
                                output.write(bytes, 0, size)
                                prefix += size
                            }
                        }
                        output.fd.sync()
                    }
                    if (expected != null && prefix != expected)
                        throw RetryableAudioCacheException("Incomplete prefetched audio")
                    check(prefix > 0) { "Empty audio" }
                }
            java.nio.file.Files.move(
                partial.toPath(),
                complete.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            complete.setLastModified(System.currentTimeMillis())
            trim(key)
            return complete
        }

        @Synchronized
        fun acquire() {
            pins++
        }

        @Synchronized
        fun release() {
            pins--
            if (pins == 0 && currentKey != key) worker?.cancel()
        }

        @Synchronized
        internal fun cancelSpeculation() {
            if (pins == 0) worker?.cancel()
        }

        internal fun disposable(): Boolean =
            pins == 0 && key != currentKey && worker?.isActive != true
    }

    private fun trim(keep: String) {
        val files =
            root
                .listFiles()
                ?.filter { it.extension in setOf("audio", "part") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= maximumBytes) break
            val key = file.nameWithoutExtension
            if (key == keep || entries[key]?.disposable() == false) continue
            val bytes = file.length()
            if (file.delete()) {
                size -= bytes
                File(root, "$key.json").delete()
                entries.remove(key)
            }
        }
    }
}

private fun File.outputStreamForAppend(append: Boolean) = java.io.FileOutputStream(this, append)
