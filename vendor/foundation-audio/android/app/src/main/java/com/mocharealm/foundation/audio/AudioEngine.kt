package com.mocharealm.foundation.audio

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Process-wide entry point for the native playback engine. */
object AudioEngine {
    private const val METADATA_MAGIC = 0x3144464D
    private val playbackStatusBuffer = ThreadLocal.withInitial {
        ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
    }
    init {
        System.loadLibrary("jni_bridge")
    }

    /** Initialize Android audio services. Safe to call more than once. */
    external fun init(context: Context)

    /** Replace the queue with [path] and begin playback. */
    external fun play(path: String): Boolean

    /**
     * Replace the queue with an audio file descriptor and begin playback.
     * The engine duplicates [fd]; the caller retains ownership of the original.
     */
    external fun playFd(fd: Int): Boolean

    external fun pause()
    external fun resume()

    /** Stop playback without clearing the pending queue. */
    external fun stop()

    /** Add a path to the end of the queue. */
    external fun addToQueue(path: String): Boolean

    /**
     * Add a file descriptor to the queue. The engine duplicates [fd]; the
     * caller may close the original after this method returns.
     */
    external fun addToQueueFd(fd: Int): Boolean

    /** Clear all queued items. The current track continues until stopped. */
    external fun clearQueue()

    /** Select and play a zero-based queue item. */
    external fun setQueueIndex(index: Int): Boolean

    /** Current zero-based queue item, or `-1` when no item is selected. */
    external fun getQueueIndex(): Int

    /** Move a queued item without interrupting the current stream. */
    external fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean

    /** Seek to an absolute position. Positions beyond the duration are clamped. */
    external fun seekTo(positionMs: Long): Boolean

    external fun skipToNext(): Boolean
    external fun skipToPrevious(): Boolean

    /** Configure crossfade duration; valid values are 0..60,000 ms. */
    external fun setCrossfadeDuration(durationMs: Int): Boolean

    /**
     * When enabled, track-to-track crossfades use vocal-mask + Mid/Side
     * soundstage automation (outgoing vocals duck while residual retreats;
     * incoming Mid rises in place and Side approaches from far).
     */
    external fun setSoundstageCrossfade(enabled: Boolean)

    /**
     * Enable/disable karaoke vocal suppression. Enabling prepares then ramps
     * mask α 0→1; disabling ramps 1→0.
     */
    external fun setKaraokeEnabled(enabled: Boolean)

    /** Karaoke α ramp duration in milliseconds (50..10_000). */
    external fun setKaraokeFadeMs(fadeMs: Int)

    /**
     * Karaoke status: [state] is 0=Off, 1=Preparing, 2=Active, 3=FadingOut;
     * [alpha] is the current wet mix in 0..1.
     */
    data class KaraokeStatus(val state: Int, val alpha: Float)

    fun getKaraokeStatus(): KaraokeStatus {
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        if (!getKaraokeStatusDirect(buffer)) {
            return KaraokeStatus(0, 0f)
        }
        buffer.rewind()
        val state = buffer.int
        val alpha = buffer.float
        return KaraokeStatus(state, alpha)
    }

    private external fun getKaraokeStatusDirect(buffer: ByteBuffer): Boolean

    /** Enable or disable EBU R128 gain normalization. */
    external fun setEbuR128Enabled(enabled: Boolean)

    /**
     * Backward-compatible alias for [setEbuR128Enabled]. New code should use
     * the EBU R128 name.
     */
    @Deprecated("Use setEbuR128Enabled")
    fun setReplayGainEnabled(enabled: Boolean) = setEbuR128Enabled(enabled)

    /** Set or clear the calculated EBU R128 gain for one queued item. */
    external fun setQueueEbuR128Gain(index: Int, gainDb: Float): Boolean

    /** Backward-compatible alias for [setQueueEbuR128Gain]. */
    @Deprecated("Use setQueueEbuR128Gain")
    fun setQueueReplayGain(index: Int, gainDb: Float) =
        setQueueEbuR128Gain(index, gainDb)

    /** Returns track gains followed by the jointly-gated EBU R128 album gain. */
    external fun calculateEbuR128Album(fds: IntArray): FloatArray?

    /** Backward-compatible alias for [calculateEbuR128Album]. */
    @Deprecated("Use calculateEbuR128Album")
    fun calculateReplayGainAlbum(fds: IntArray) = calculateEbuR128Album(fds)

    /**
     * Provides eighteen native-owned, 16 kHz mono YAMNet windows to [block]. The
     * direct PCM buffer is valid only for the duration of the callback.
     */
    fun <T> withMusicAnalysisPcm(fd: Int, block: (ByteBuffer) -> T): T? {
        val buffer = decodeMusicAnalysisPcm(fd) ?: return null
        return try {
            buffer.order(ByteOrder.nativeOrder())
            block(buffer)
        } finally {
            releaseMusicAnalysisPcm(buffer)
        }
    }

    /** Prefer this typed overload in application code. */
    fun setPlayMode(mode: PlayMode) = setPlayMode(mode.nativeValue)

    /** Native compatibility entry point. Unknown values are rejected. */
    external fun setPlayMode(mode: Int): Boolean

    external fun getMetadata(): TrackMetadata?
    external fun getDiagnosticLog(): String
    fun getPlaybackStatus(): PlaybackStatus? {
        val buffer = requireNotNull(playbackStatusBuffer.get())
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (!getPlaybackStatusDirect(buffer)) return null
        buffer.rewind()
        return PlaybackStatus(
            currentMs = buffer.long,
            durationMs = buffer.long,
            isPaused = buffer.int != 0,
            speed = buffer.float,
            playbackGeneration = buffer.long,
        )
    }

    private val spectrumBuffer=ThreadLocal.withInitial {ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)}
    /** Linear three-band energy of samples actually submitted to the output device. */
    fun readSpectrumPower(output:FloatArray):Boolean {
        require(output.size>=3)
        val buffer=requireNotNull(spectrumBuffer.get())
        buffer.clear()
        if(!getSpectrumPowerDirect(buffer)){output.fill(0f);return false}
        buffer.rewind()
        for(index in 0..2)output[index]=buffer.float
        return true
    }
    private external fun getSpectrumPowerDirect(buffer:ByteBuffer):Boolean

    /** Read tags and optional embedded cover art without starting playback. */
    external fun extractMetadataFd(fd: Int, tags: MutableMap<String, String>): ByteArray?

    /** Decode one Rust-owned direct metadata buffer within its valid lifetime. */
    fun <T> withExtractedMetadataFd(
        fd: Int,
        block: (tags: Map<String, String>, cover: ByteBuffer?) -> T,
    ): T? {
        val buffer = extractMetadataFdDirect(fd) ?: return null
        return try {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == METADATA_MAGIC) { "Invalid metadata wire" }
            val tagCount = buffer.readCount()
            val tags = LinkedHashMap<String, String>(tagCount)
            repeat(tagCount) {
                tags[buffer.readString()] = buffer.readString()
            }
            val coverLength = buffer.readNonNegativeInt()
            require(coverLength <= buffer.remaining()) { "Truncated metadata cover" }
            val cover = if (coverLength > 0) {
                buffer.slice().apply { limit(coverLength) }.asReadOnlyBuffer()
            } else null
            block(tags, cover)
        } catch (_: Exception) {
            null
        } finally {
            releaseMetadataBuffer(buffer)
        }
    }

    private fun ByteBuffer.readCount(): Int = int.also {
        require(it in 0..1_000_000) { "Invalid metadata count" }
    }

    private fun ByteBuffer.readNonNegativeInt(): Int = int.also {
        require(it >= 0) { "Invalid metadata length" }
    }

    private fun ByteBuffer.readString(): String {
        val length = readCount()
        require(length <= remaining()) { "Truncated metadata string" }
        val value = slice().apply { limit(length) }
        position(position() + length)
        return StandardCharsets.UTF_8.decode(value).toString()
    }

    private external fun getPlaybackStatusDirect(buffer: ByteBuffer): Boolean
    private external fun extractMetadataFdDirect(fd: Int): ByteBuffer?
    private external fun releaseMetadataBuffer(buffer: ByteBuffer)
    private external fun decodeMusicAnalysisPcm(fd: Int): ByteBuffer?
    private external fun releaseMusicAnalysisPcm(buffer: ByteBuffer)
}
