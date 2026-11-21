// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import androidx.test.platform.app.InstrumentationRegistry
import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.platform.AndroidAudio
import com.mocharealm.bell.data.platform.androidClient
import com.mocharealm.bell.domain.lyrics.display
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses only the isolated lyric fixture server, forwarded with adb reverse tcp:18097 tcp:18097. */
class PlaybackIntegrationTest {
    @Test
    fun lanServerIsReachableWithoutAdbPortForwarding() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("lanServer")
        assumeTrue("Provide -e lanServer to test a real LAN server", server != null)
        val client = androidClient()
        try {
            val response = client.get("${server!!.trimEnd('/')}/System/Info/Public")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ServerName", ignoreCase = true))
        } finally {
            client.close()
        }
    }

    @Test
    fun jellyfinRangePlaybackSeekPauseAndLyrics() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = androidClient()
        val api = JellyfinApi(client)
        val session =
            api.login(
                "http://127.0.0.1:18097",
                "lyric-audit",
                "LocalFixtureOnly123!",
                "jellify-instrumentation",
            )
        val songs = api.items(session, "Audio")
        assertTrue("Fixture music library missing", songs.isNotEmpty())
        val player = AndroidAudio(context, client) {}
        try {
            val song = songs.first()
            player.play(api.stream(session, song.id), song.durationMs)
            delay(1200)
            val playing = player.snapshot()
            assertFalse("Native engine did not start", playing.paused)
            assertTrue("Playback clock did not advance", playing.positionMs > 100)
            val seek = (song.durationMs / 2).coerceAtMost(5000)
            player.seek(seek)
            delay(350)
            assertTrue(
                "Range seek did not reach target",
                player.snapshot().positionMs >= seek - 150,
            )
            player.pause(true)
            delay(200)
            val paused = player.snapshot()
            assertTrue(paused.paused)
            delay(300)
            assertTrue(
                "Paused clock kept advancing",
                kotlin.math.abs(player.snapshot().positionMs - paused.positionMs) < 100,
            )
            player.pause(false)
            delay(250)
            assertFalse(player.snapshot().paused)
            val lyrics = api.lyrics(session, song.id).display(song.durationMs)
            assertTrue("Fixture lyric tracks missing", lyrics.lines.isNotEmpty())
        } finally {
            player.close()
            client.close()
        }
    }
}
