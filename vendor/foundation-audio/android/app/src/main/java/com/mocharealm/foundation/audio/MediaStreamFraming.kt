package com.mocharealm.foundation.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire framing for the fabric `media` stream, mirrored from the Rust
 * `MediaStreamHeader` (fabric-audio-adapter). Sources write this prefix before
 * the compressed container bytes; renderers strip it (natively, via
 * [AudioEngine.playMediaStreamFd] / [FabricRenderer.attachMedia]).
 *
 * Layout, all big-endian: `generation:u64 · flags:u32 · byteLength:u64 ·
 * startPositionMs:u64`.
 *
 * `startPositionMs` is the media-timeline position at which the stream's byte 0
 * begins — `0` from the top of the track; a seek restarts delivery on a fresh
 * stream carrying the new position. Use this instead of hand-rolling the bytes:
 * the layout is versioned together with the native engine in the same artifact.
 */
data class MediaStreamHeader(
    val generation: Long,
    val flags: Int = FLAG_COMPRESSED_CONTAINER,
    /** Total body bytes when known up front; `0` for open-ended streams. */
    val byteLength: Long = 0,
    val startPositionMs: Long = 0,
) {
    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(generation)
            putInt(flags)
            putLong(byteLength)
            putLong(startPositionMs)
        }.array()

    companion object {
        const val HEADER_BYTES = 28
        const val FLAG_COMPRESSED_CONTAINER = 1
        const val FLAG_END_OF_OBJECT = 1 shl 1

        fun decode(bytes: ByteArray): MediaStreamHeader? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return MediaStreamHeader(
                generation = buffer.long,
                flags = buffer.int,
                byteLength = buffer.long,
                startPositionMs = buffer.long,
            )
        }
    }
}
