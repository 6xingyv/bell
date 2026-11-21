package com.mocharealm.foundation.audio

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime

data class MusicEmbedding(
    val modelVersion: String,
    val values: FloatArray,
)

/** Metadata-independent neural music analysis backed by LiteRT/YAMNet. */
class MusicAnalysis private constructor(
    private val interpreter: InterpreterApi,
) : Closeable {
    private val inferenceLock = Mutex()

    suspend fun analyzeFd(fd: Int): MusicEmbedding? = withContext(Dispatchers.Default) {
        inferenceLock.withLock {
            AudioEngine.withMusicAnalysisPcm(fd) { pcm -> analyzePcm(pcm) }
        }
    }

    private fun analyzePcm(pcm: ByteBuffer): MusicEmbedding? {
        if (pcm.capacity() != WINDOW_COUNT * WINDOW_BYTES) return null
        val mean = FloatArray(OUTPUT_SIZE)
        val output = ByteBuffer.allocateDirect(OUTPUT_SIZE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        repeat(WINDOW_COUNT) { windowIndex ->
            val byteOffset = windowIndex * WINDOW_BYTES
            val input = pcm.duplicate().apply {
                position(byteOffset)
                limit(byteOffset + WINDOW_BYTES)
            }.slice().order(ByteOrder.nativeOrder())
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            repeat(OUTPUT_SIZE) { index -> mean[index] += output.float }
        }

        var normSquared = 0.0
        for (index in mean.indices) {
            mean[index] /= WINDOW_COUNT.toFloat()
            normSquared += mean[index].toDouble() * mean[index].toDouble()
        }
        val norm = kotlin.math.sqrt(normSquared).toFloat()
        if (!norm.isFinite() || norm <= 1e-8f) return null
        for (index in mean.indices) mean[index] /= norm
        return MusicEmbedding(MODEL_VERSION, mean)
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_VERSION = "yamnet-mediapipe-float32-v3"
        const val OUTPUT_SIZE = 521
        private const val MODEL_ASSET = "yamnet.tflite"
        private const val WINDOW_COUNT = 18
        private const val WINDOW_SAMPLES = 15_600
        private const val WINDOW_BYTES = WINDOW_SAMPLES * Float.SIZE_BYTES

        suspend fun create(context: Context): MusicAnalysis = withContext(Dispatchers.IO) {
            val model = context.assets.openFd(MODEL_ASSET).use { asset ->
                FileInputStream(asset.fileDescriptor).channel.use { channel ->
                    channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        asset.startOffset,
                        asset.declaredLength,
                    )
                }
            }
            Tasks.await(initializationTask(context))
            val options = InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            MusicAnalysis(InterpreterApi.create(model, options))
        }

        fun prewarm(context: Context) {
            initializationTask(context)
        }

        @Volatile
        private var sharedInitializationTask: Task<Void>? = null

        private fun initializationTask(context: Context): Task<Void> = synchronized(this) {
            sharedInitializationTask?.let { return@synchronized it }
            val task = TfLite.initialize(context.applicationContext)
            sharedInitializationTask = task
            task.addOnFailureListener {
                synchronized(this) {
                    if (sharedInitializationTask === task) sharedInitializationTask = null
                }
            }
            task
        }
    }
}
