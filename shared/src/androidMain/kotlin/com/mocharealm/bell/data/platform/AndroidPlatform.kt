// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.platform

import android.content.Context
import android.os.*
import android.os.storage.StorageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.data.network.wireJson
import com.mocharealm.bell.data.playback.AudioRangeReader
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.playback.readCompleteFileRange
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.playback.monotonicMillis
import com.mocharealm.bell.domain.repository.PlaybackSource
import com.mocharealm.foundation.audio.AudioEngine
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun androidClient() = HttpClient(OkHttp) { configureClient(this) }

class AndroidSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("jellify-session", Context.MODE_PRIVATE)
    override val deviceId: String =
        prefs.getString("device", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey("jellify-session", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "jellify-session",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    override fun load(): Session? = runCatching {
        val bytes = Base64.decode(prefs.getString("session", null) ?: return null, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
        wireJson.decodeFromString<Session>(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
        )
    }
        .getOrNull()

    override fun save(session: Session?) {
        if (session == null) {
            prefs.edit().remove("session").apply()
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(wireJson.encodeToString(session).encodeToByteArray())
        prefs
            .edit()
            .putString("session", Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP))
            .apply()
    }
}

/**
 * A seekable, bounded-memory HTTP range reader. Native pread calls execute on a dedicated handler.
 */
class RangeFile(
    private val client: HttpClient,
    private val url: String,
    private val length: Long,
    private val disk: StreamingAudioCache.Entry? = null,
) : ProxyFileDescriptorCallback() {
    private val reader = AudioRangeReader(client, url, length)
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private var reads = 0
    private var bytesRead = 0L

    init {
        disk?.acquire()
    }

    fun cancel() {
        if (released.compareAndSet(false, true)) {
            reader.close()
            disk?.release()
        }
    }

    override fun onGetSize(): Long {
        android.util.Log.i("BellAudio", "proxy length=$length")
        return length
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (released.get()) throw ErrnoException("read", OsConstants.EBADF)
        if (offset < 0 || size < 0 || size > data.size)
            throw ErrnoException("read", OsConstants.EINVAL)
        val before = monotonicMillis()
        reads++
        if (reads <= 3)
            android.util.Log.i("BellAudio", "proxy read begin n=$reads offset=$offset size=$size")
        return try {
            var allCached = true
            val result = runBlocking {
                readCompleteFileRange(offset, size, length) { position, count, destination ->
                    val cached = disk?.prefixRead(position, count, data, destination) ?: 0
                    if (cached > 0) cached
                    else {
                        val awaited = disk?.awaitPrefix(position, count, data, destination) ?: 0
                        if (awaited > 0) awaited
                        else {
                            allCached = false
                            reader.read(position, count, data, destination)
                        }
                    }
                }
            }
            bytesRead += result
            val elapsed = monotonicMillis() - before
            if (reads <= 3)
                android.util.Log.i(
                    "BellAudio",
                    "proxy read end n=$reads ms=$elapsed bytes=$result total=$bytesRead",
                )
            if (elapsed > 150)
                android.util.Log.w(
                    "BellAudio",
                    "read ms=$elapsed offset=$offset bytes=$result cached=$allCached",
                )
            result
        } catch (e: Exception) {
            if (!released.get())
                android.util.Log.e(
                    "BellAudio",
                    "Range read failed offset=$offset size=$size type=${e::class.simpleName}",
                )
            throw ErrnoException("http range read", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        cancel()
    }
}

class AndroidAudio(
    private val context: Context,
    private val client: HttpClient,
    private val cache: StreamingAudioCache =
        StreamingAudioCache(java.io.File(context.cacheDir, "audio-stream"), client),
    private val onStart: () -> Unit,
) : AudioPlayer {
    private val thread = HandlerThread("Bell range reader").apply { start() }
    private val lock = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var activeRange: RangeFile? = null
    private var pendingRange: RangeFile? = null
    @Volatile private var desiredAutoMix = false
    private val transitionLock = Any()
    @Volatile private var generation = 0L
    private var pendingKey: Long? = null
    private var pendingSource: PlaybackSource? = null
    private var pendingDuration = 0L
    private var transitionCount = 0L
    private var nativeEntryKey: Long? = null
    private var lastAdvanceAt = 0L
    private var nativePosition = -1L
    private var wasBuffering = false
    private var failureLogged = false
    private var duration = 0L
    @Volatile private var started = false
    @Volatile private var explicitlyPaused = true
    @Volatile private var lastSample = AudioSnapshot()

    init {
        AudioEngine.init(context)
    }

    override suspend fun play(url: String, durationMs: Long) =
        play(PlaybackSource(url, url.substringBefore('?')), durationMs)

    override suspend fun play(source: PlaybackSource, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val expected = playGeneration.incrementAndGet()
            // Closing the Java read scope wakes a worker blocked in native pread/probing.
            // Do this before taking the command mutex or waiting for AudioEngine.stop().
            activeRange?.cancel()
            synchronized(transitionLock) {
                generation++
                pendingRange?.cancel()
                pendingRange = null
                pendingKey = null
                pendingSource = null
            }
            val began = monotonicMillis()
            lock.withLock {
                ensureActive()
                if (expected != playGeneration.get()) return@withLock
                started = false
                explicitlyPaused = true
                lastSample = AudioSnapshot()
                nativePosition = -1
                lastAdvanceAt = monotonicMillis()
                wasBuffering = false
                failureLogged = false
                var at = monotonicMillis()
                AudioEngine.stop()
                descriptor?.close()
                descriptor = null
                android.util.Log.i("BellAudio", "stop ms=${monotonicMillis()-at}")
                cache.prioritize(source)
                val fd =
                    if (source.url.startsWith("file:")) {
                        activeRange = null
                        ParcelFileDescriptor.open(
                            java.io.File(java.net.URI(source.url)),
                            ParcelFileDescriptor.MODE_READ_ONLY,
                        )
                    } else {
                        val entry = cache.entry(source)
                        entry.start()
                        at = monotonicMillis()
                        val length = entry.length()
                        android.util.Log.i(
                            "BellAudio",
                            "length ms=${monotonicMillis()-at} bytes=$length",
                        )
                        ensureActive()
                        if (expected != playGeneration.get()) return@withLock
                        val callback = RangeFile(client, source.url, length, entry)
                        activeRange = callback
                        context
                            .getSystemService(StorageManager::class.java)
                            .openProxyFileDescriptor(
                                ParcelFileDescriptor.MODE_READ_ONLY,
                                callback,
                                Handler(thread.looper),
                            )
                    }
                descriptor = fd
                duration = durationMs
                try {
                    at = monotonicMillis()
                    check(AudioEngine.playFd(fd.fd)) { "foundation.audio could not open audio" }
                    android.util.Log.i(
                        "BellAudio",
                        "native open ms=${monotonicMillis()-at} total=${monotonicMillis()-began} local=${source.url.startsWith("file:")}",
                    )
                    ensureActive()
                    if (expected != playGeneration.get()) return@withLock
                    transitionCount = 0
                    nativeEntryKey = null
                    explicitlyPaused = false
                    started = true
                    onStart()
                } catch (e: Exception) {
                    activeRange?.cancel()
                    AudioEngine.stop()
                    fd.close()
                    descriptor = null
                    throw e
                }
            }
        }

    private fun acceptNativeTransition() {
        if (pendingKey != null && AudioEngine.getQueueIndex() >= 0) {
            nativeEntryKey = pendingKey
            pendingKey = null
            transitionCount++
            duration = pendingDuration
            activeRange?.cancel()
            activeRange = pendingRange
            pendingRange = null
            descriptor?.close()
            descriptor = null
            pendingSource?.let { cache.prioritize(it) }
            pendingSource = null
        }
    }

    override suspend fun prepareTransition(source: PlaybackSource, key: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val expected = generation
            var callback: RangeFile? = null
            val fd =
                if (source.url.startsWith("file:"))
                    ParcelFileDescriptor.open(
                        java.io.File(java.net.URI(source.url)),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                else {
                    val entry = cache.entry(source)
                    entry.start()
                    val length = entry.length()
                    ensureActive()
                    RangeFile(client, source.url, length, entry).let {
                        callback = it
                        context
                            .getSystemService(StorageManager::class.java)
                            .openProxyFileDescriptor(
                                ParcelFileDescriptor.MODE_READ_ONLY,
                                it,
                                Handler(thread.looper),
                            )
                    }
                }
            var installed = false
            try {
                fd.use {
                    lock.withLock {
                        ensureActive()
                        if (expected != generation || !started) return@withLock
                        synchronized(transitionLock) { acceptNativeTransition() }
                        if (pendingKey == key) return@withLock
                        AudioEngine.clearQueue()
                        ensureActive()
                        if (expected != generation) return@withLock
                        if (AudioEngine.addToQueueFd(fd.fd))
                            synchronized(transitionLock) {
                                if (expected == generation) {
                                    pendingKey = key
                                    pendingDuration = durationMs
                                    pendingSource = source
                                    pendingRange = callback
                                    installed = true
                                }
                            }
                    }
                }
            } finally {
                if (!installed) callback?.cancel()
            }
        }

    override fun cancelTransition() {
        val expected: Long
        val clear: Boolean
        synchronized(transitionLock) {
            generation++
            expected = generation
            acceptNativeTransition()
            clear = pendingKey != null
            pendingRange?.cancel()
            pendingRange = null
            pendingKey = null
            pendingSource = null
        }
        if (clear)
            controlScope.launch {
                lock.withLock { if (expected == generation) AudioEngine.clearQueue() }
            }
    }

    override fun setAutoMix(enabled: Boolean) {
        desiredAutoMix = enabled
        controlScope.launch {
            lock.withLock {
                AudioEngine.setCrossfadeDuration(if (desiredAutoMix) 4000 else 0)
                AudioEngine.setSoundstageCrossfade(desiredAutoMix)
            }
        }
    }

    override fun pause(paused: Boolean) {
        explicitlyPaused = paused
        if (paused) AudioEngine.pause() else AudioEngine.resume()
    }

    override fun seek(positionMs: Long) {
        AudioEngine.seekTo(positionMs)
    }

    override fun readSpectrumPower(output: FloatArray): Boolean {
        if (!started || explicitlyPaused) {
            output.fill(0f)
            return false
        }
        return AudioEngine.readSpectrumPower(output)
    }

    override fun snapshot(): AudioSnapshot =
        synchronized(transitionLock) {
            acceptNativeTransition()
            val status = AudioEngine.getPlaybackStatus()
            if (status != null) {
                val now = monotonicMillis()
                if (status.currentMs != nativePosition || status.isPaused) {
                    nativePosition = status.currentMs
                    lastAdvanceAt = now
                }
                val stalled = !status.isPaused && now - lastAdvanceAt >= 200
                if (stalled != wasBuffering) {
                    wasBuffering = stalled
                    android.util.Log.w(
                        "BellAudio",
                        "Output buffering=$stalled position=${status.currentMs} ${AudioEngine.getDiagnosticLog()}",
                    )
                }
                return@synchronized AudioSnapshot(
                        status.currentMs,
                        status.durationMs.takeIf { it > 0 } ?: duration,
                        status.isPaused,
                        transitionCount = transitionCount,
                        nativeEntryKey = nativeEntryKey,
                        buffering = stalled,
                    )
                    .also { lastSample = it }
            }
            // foundation.audio's JNI returns null for State.stopped, including a fully drained EOF.
            // Only an explicit app stop clears ownership; a natural EOF is a completion event.
            val total = lastSample.durationMs.takeIf { it > 0 } ?: duration
            if (started && total > 0 && lastSample.positionMs < total - 1500) {
                if (!explicitlyPaused && !failureLogged) {
                    failureLogged = true
                    android.util.Log.e(
                        "BellAudio",
                        "Native stopped early ${AudioEngine.getDiagnosticLog()}",
                    )
                }
                return@synchronized lastSample.copy(
                    paused = explicitlyPaused,
                    buffering = !explicitlyPaused,
                    failed = true,
                    ended = false,
                    sampledAt = monotonicMillis(),
                    transitionCount = transitionCount,
                    nativeEntryKey = nativeEntryKey,
                )
            }
            if (started)
                lastSample.copy(
                    positionMs = total,
                    durationMs = total,
                    paused = true,
                    sampledAt = monotonicMillis(),
                    ended = !explicitlyPaused,
                    buffering = false,
                    transitionCount = transitionCount,
                    nativeEntryKey = nativeEntryKey,
                )
            else AudioSnapshot()
        }

    override fun stop() {
        val expected = playGeneration.incrementAndGet()
        activeRange?.cancel()
        synchronized(transitionLock) {
            generation++
            pendingRange?.cancel()
            pendingRange = null
            pendingKey = null
            pendingSource = null
            started = false
            explicitlyPaused = true
            lastSample = AudioSnapshot()
        }
        controlScope.launch {
            lock.withLock {
                if (expected == playGeneration.get()) {
                    AudioEngine.stop()
                    descriptor?.close()
                    descriptor = null
                }
            }
        }
    }

    override fun close() {
        stop()
        controlScope
            .launch {
                lock.withLock {
                    AudioEngine.stop()
                    descriptor?.close()
                    descriptor = null
                }
                thread.quitSafely()
            }
            .invokeOnCompletion { controlScope.cancel() }
    }
}
