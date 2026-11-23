// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.platform

import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.data.network.wireJson
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.repository.PlaybackSource
import com.sun.jna.*
import com.sun.jna.platform.win32.Crypt32Util
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.*

fun desktopClient() = HttpClient(CIO) { configureClient(this) }

class DesktopSessionStore : SessionStore {
    private val prefs = Preferences.userRoot().node("com/mocharealm/jellify")
    private val windows = System.getProperty("os.name").startsWith("Windows")
    override val deviceId =
        prefs.get("device", null) ?: UUID.randomUUID().toString().also { prefs.put("device", it) }

    override fun load(): Session? = runCatching {
        val data = Base64.getDecoder().decode(prefs.get("session", null) ?: return null)
        wireJson.decodeFromString<Session>(
            (if (windows) Crypt32Util.cryptUnprotectData(data) else data).decodeToString()
        )
    }
        .getOrNull()

    override fun save(session: Session?) {
        if (session == null) {
            prefs.remove("session")
            return
        }
        // Windows credential encryption is bound to the logged-in Windows account.
        val bytes = wireJson.encodeToString(session).encodeToByteArray()
        if (windows)
            prefs.put(
                "session",
                Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(bytes)),
            )
        // Other desktop systems keep the token in memory until their keychain backend is added.
    }
}

private interface FoundationAudio : Library {
    fun fa_abi_version(): Int

    fun fa_engine_create(headless: Byte): Pointer?

    fun fa_engine_destroy(engine: Pointer)

    fun fa_engine_play(engine: Pointer, path: String): Byte

    fun fa_engine_add_path(engine: Pointer, path: String): Byte

    fun fa_engine_clear_queue(engine: Pointer): Byte

    fun fa_engine_queue_index(engine: Pointer): Int

    fun fa_engine_crossfade(engine: Pointer, ms: Int, soundstage: Byte): Byte

    fun fa_engine_pause(engine: Pointer, paused: Byte)

    fun fa_engine_stop(engine: Pointer): Byte

    fun fa_engine_seek(engine: Pointer, ms: Long): Byte

    fun fa_engine_spectrum(engine: Pointer, output: Pointer): Byte

    fun fa_engine_status(engine: Pointer, output: Pointer)
}

class DesktopAudio(
    private val client: HttpClient,
    private val cache: StreamingAudioCache =
        StreamingAudioCache(
            File(
                System.getProperty("jellify.cacheDir", System.getProperty("java.io.tmpdir")),
                "audio-stream",
            ),
            client,
        ),
) : AudioPlayer {
    private val native by lazy { Native.load("foundation_audio", FoundationAudio::class.java) }
    private val nativeLock = Any()
    private var closed = false
    private var engine: Pointer? = null
    private var current: File? = null
    private var autoMixEnabled = false
    private var pendingKey: Long? = null
    private var pendingDuration = 0L
    private var pendingSource: PlaybackSource? = null
    private var transitionCount = 0L
    private var nativeEntryKey: Long? = null
    private var generation = 0L
    private var duration = 0L

    override suspend fun play(url: String, durationMs: Long) =
        play(PlaybackSource(url, url.substringBefore('?')), durationMs)

    override suspend fun play(source: PlaybackSource, durationMs: Long) =
        withContext(Dispatchers.IO) {
            stop()
            cache.prioritize(source)
            val file =
                if (source.url.startsWith("file:")) File(java.net.URI(source.url))
                else cache.entry(source).awaitComplete()
            ensureActive()
            synchronized(nativeLock) {
                check(!closed) { "Audio player is closed" }
                val handle =
                    engine
                        ?: (native.fa_engine_create(0) ?: error("Audio device unavailable")).also {
                            engine = it
                        }
                check(native.fa_engine_play(handle, file.absolutePath).toInt() != 0) {
                    "Audio decode failed"
                }
                transitionCount = 0
                nativeEntryKey = null
                pendingKey = null
                native.fa_engine_crossfade(
                    handle,
                    if (autoMixEnabled) 4000 else 0,
                    if (autoMixEnabled) 1 else 0,
                )
                duration = durationMs
                current = file
            }
        }

    private fun acceptNativeTransition(handle: Pointer) {
        if (pendingKey != null && native.fa_engine_queue_index(handle) >= 0) {
            nativeEntryKey = pendingKey
            pendingKey = null
            transitionCount++
            duration = pendingDuration
            pendingSource?.let { cache.prioritize(it) }
            pendingSource = null
        }
    }

    override suspend fun prepareTransition(source: PlaybackSource, key: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val expected = synchronized(nativeLock) { generation }
            val file =
                if (source.url.startsWith("file:")) File(java.net.URI(source.url))
                else cache.entry(source).awaitComplete()
            ensureActive()
            synchronized(nativeLock) {
                val handle = engine ?: return@synchronized
                if (expected != generation || closed) return@synchronized
                acceptNativeTransition(handle)
                if (pendingKey == key) return@synchronized
                native.fa_engine_clear_queue(handle)
                if (native.fa_engine_add_path(handle, file.absolutePath).toInt() != 0) {
                    pendingKey = key
                    pendingDuration = durationMs
                    pendingSource = source
                }
            }
        }

    override fun cancelTransition() =
        synchronized(nativeLock) {
            generation++
            engine?.let { handle ->
                acceptNativeTransition(handle)
                if (pendingKey != null) {
                    native.fa_engine_clear_queue(handle)
                    pendingKey = null
                    pendingSource = null
                }
            }
            Unit
        }

    override fun setAutoMix(enabled: Boolean) =
        synchronized(nativeLock) {
            autoMixEnabled = enabled
            engine?.let {
                native.fa_engine_crossfade(it, if (enabled) 4000 else 0, if (enabled) 1 else 0)
            }
            Unit
        }

    override fun pause(paused: Boolean) =
        synchronized(nativeLock) {
            engine?.let { native.fa_engine_pause(it, if (paused) 1 else 0) }
            Unit
        }

    override fun seek(positionMs: Long) =
        synchronized(nativeLock) {
            engine?.let { native.fa_engine_seek(it, positionMs) }
            Unit
        }

    override fun readSpectrumPower(output: FloatArray): Boolean =
        synchronized(nativeLock) {
            val handle =
                engine
                    ?: run {
                        output.fill(0f)
                        return false
                    }
            return Memory(16).use { buffer ->
                if (native.fa_engine_spectrum(handle, buffer).toInt() == 0) {
                    output.fill(0f)
                    false
                } else {
                    for (index in 0..2) output[index] = buffer.getFloat(index * 4L)
                    true
                }
            }
        }

    override fun snapshot(): AudioSnapshot =
        synchronized(nativeLock) {
            val handle = engine ?: return AudioSnapshot()
            acceptNativeTransition(handle)
            return Memory(32).use { bytes ->
                native.fa_engine_status(handle, bytes)
                val position = bytes.getLong(0)
                val total = bytes.getLong(8).takeIf { it > 0 } ?: duration
                val paused = bytes.getInt(16) != 0
                AudioSnapshot(
                    position,
                    total,
                    paused,
                    ended = !paused && total > 0 && position >= total - 25,
                    transitionCount = transitionCount,
                    nativeEntryKey = nativeEntryKey,
                )
            }
        }

    override fun stop() =
        synchronized(nativeLock) {
            generation++
            pendingKey = null
            pendingSource = null
            engine?.let { native.fa_engine_stop(it) }
            current = null
        }

    override fun close() =
        synchronized(nativeLock) {
            closed = true
            stop()
            engine?.let { native.fa_engine_destroy(it) }
            engine = null
        }
}
