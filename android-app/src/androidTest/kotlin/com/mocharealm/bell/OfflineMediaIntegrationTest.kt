// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.data.platform.AndroidAudio
import com.mocharealm.bell.data.platform.androidClient
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.storage.FileMediaLibrary
import com.mocharealm.bell.domain.repository.SavedPlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses the existing isolated lyric fixture only; never reads or replaces the user's queue/account.
 */
class OfflineMediaIntegrationTest {
    @Test
    fun downloadedAudioAndAllLyricsWorkWithNetworkingDisabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-verification-" + UUID.randomUUID())
        val client = androidClient()
        val api = JellyfinApi(client)
        val session =
            api.login(
                "http://127.0.0.1:18097",
                "lyric-audit",
                "LocalFixtureOnly123!",
                "offline-instrumentation",
            )
        val song = api.items(session, "Audio").first()
        val expected = api.lyrics(session, song.id)
        val media =
            FileMediaLibrary(File(root, "media"), StreamingAudioCache(File(root, "stream"), client))
        val offlineClient =
            HttpClient(MockEngine { error("Offline playback attempted a network request") }) {
                configureClient(this)
            }
        val reopened =
            FileMediaLibrary(
                File(root, "media"),
                StreamingAudioCache(File(root, "stream"), offlineClient),
            )
        val player = AndroidAudio(context, offlineClient, reopened.audioCache) {}
        try {
            media.download(api, session, song)
            val source = reopened.source(JellyfinApi(offlineClient), session, song)
            assertTrue(source.url.startsWith("file:"))
            assertEquals(expected, reopened.lyrics(session, song.id))
            player.play(source, song.durationMs)
            delay(700)
            assertFalse(player.snapshot().paused)
            assertTrue(player.snapshot().positionMs > 100)
            player.pause(true)
            assertTrue(player.snapshot().paused)
            player.seek(minOf(1000, song.durationMs / 2))
            player.pause(false)
            delay(300)
            assertFalse(player.snapshot().paused)
            reopened.savePlayback(session, SavedPlayback(listOf(song), 0, 500, true, true, true))
            val checkpoint = reopened.loadPlayback(session)!!
            assertEquals(song.id, checkpoint.queue.single().id)
            assertEquals(500L, checkpoint.positionMs)
        } finally {
            player.close()
            reopened.close()
            media.close()
            offlineClient.close()
            client.close()
        }
    }

    @Test
    fun meshAlbumPreparesAndCompilesOnTheDevice() =
        runBlocking(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val colors =
                IntArray(64 * 64) { index ->
                    android.graphics.Color.rgb(index % 64 * 4, index / 64 * 4, 160)
                }
            bitmap.setPixels(colors, 0, 64, 0, 0, 64, 64)
            try {
                val renderer =
                    Class.forName("com.mocharealm.bell.ui.screen.player.mesh.MeshGradientRenderer")
                val companion = renderer.getDeclaredField("Companion").get(null)
                val prepare =
                    companion.javaClass.declaredMethods.first {
                        it.name == "prepareAlbum" && it.parameterTypes.size == 3
                    }
                val album = prepare.invoke(companion, bitmap, 20, null)
                assertNotNull(album)
                val positions =
                    album.javaClass
                        .getDeclaredField("positions")
                        .apply { isAccessible = true }
                        .get(album) as FloatArray
                assertTrue(positions.isNotEmpty())
                assertTrue(positions.all { it.isFinite() })
            } finally {
                bitmap.recycle()
            }
        }

    @Test
    fun audibleNativePlaybackProducesSpectrumAndPauseClearsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = androidClient()
        val api = JellyfinApi(client)
        val session =
            api.login(
                "http://127.0.0.1:18097",
                "lyric-audit",
                "LocalFixtureOnly123!",
                "spectrum-instrumentation",
            )
        val player = AndroidAudio(context, client) {}
        try {
            val song = api.items(session, "Audio").first()
            player.play(api.stream(session, song.id), song.durationMs)
            val power = FloatArray(3)
            withTimeout(5000) {
                while (!player.readSpectrumPower(power) || power.sum() <= 0.000001f) delay(20)
            }
            assertTrue(power.all { it.isFinite() && it >= 0f })
            player.pause(true)
            assertFalse(player.readSpectrumPower(power))
            assertTrue(power.all { it == 0f })
        } finally {
            player.close()
            client.close()
        }
    }
}
