package com.mocharealm.foundation.audio

import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * Fabric renderer adapter over the native playback engine.
 *
 * Pairs with the fabric-sdk AAR: register the audio ability with
 * `FabricClient.registerAbility(FabricRenderer.ABILITY, handler)` and forward
 * what the handler receives — control payloads from `onInvoke` go to
 * [handleControl] (complete the invocation with the returned bytes), and the
 * hub's read descriptor from `onStreamOpen` goes to [attachMedia] for the
 * [MEDIA_STREAM] or [acceptAsset] for the [ASSET_STREAM]. All protocol state
 * (announce, assets, barrier timing, start position) lives in native code.
 *
 * [attachMedia] and [acceptAsset] block while reading the stream — call them
 * from a background thread, never the binder callback thread.
 */
class FabricRenderer(participantId: String) : Closeable {
    companion object {
        const val ABILITY = "com.mocharealm.foundation.audio.render"
        const val MEDIA_STREAM = "media"
        const val ASSET_STREAM = "asset"

        init {
            System.loadLibrary("jni_bridge")
        }

        @JvmStatic private external fun nativeCreate(participantId: String): Long
        @JvmStatic private external fun nativeHandleControl(handle: Long, payload: ByteArray): ByteArray?
        @JvmStatic private external fun nativeAttachMediaFd(handle: Long, fd: Int): Boolean
        @JvmStatic private external fun nativeAcceptAssetFd(handle: Long, fd: Int): Boolean
        @JvmStatic private external fun nativeFeedbackJson(handle: Long): ByteArray?
        @JvmStatic private external fun nativePresentationJson(handle: Long): ByteArray?
        @JvmStatic private external fun nativeCoverBytes(handle: Long): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }

    private var handle: Long = nativeCreate(participantId)

    private fun requireHandle(): Long {
        check(handle != 0L) { "FabricRenderer is closed" }
        return handle
    }

    /** Handle one control payload; returns the reply to complete the invoke with. */
    fun handleControl(payload: ByteArray): ByteArray =
        nativeHandleControl(requireHandle(), payload) ?: byteArrayOf()

    /** Attach the framed media stream. Blocking; the descriptor stays owned by the caller. */
    fun attachMedia(readFromRemote: ParcelFileDescriptor): Boolean =
        nativeAttachMediaFd(requireHandle(), readFromRemote.fd)

    /** Read and install one framed cover/lyrics asset. Blocking. */
    fun acceptAsset(readFromRemote: ParcelFileDescriptor): Boolean =
        nativeAcceptAssetFd(requireHandle(), readFromRemote.fd)

    /** Latest playback state as a JSON `AudioFeedbackDatagram`, for the feedback port. */
    fun feedbackJson(): ByteArray = nativeFeedbackJson(requireHandle()) ?: byteArrayOf()

    /**
     * Presentation metadata for the current announce (generation, title, artist,
     * album, duration, merged lyrics, …) as JSON, for the app UI. Cover art is
     * excluded — use [coverBytes].
     */
    fun presentationJson(): ByteArray = nativePresentationJson(requireHandle()) ?: byteArrayOf()

    /** Cover art for the current announce, or null when none is installed yet. */
    fun coverBytes(): ByteArray? = nativeCoverBytes(requireHandle())

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }
}
