// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.model.User
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import com.mocharealm.bell.domain.repository.PlaybackSource
import com.mocharealm.bell.domain.repository.SavedPlayback
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaybackRestoreTest {
    private val session = Session("http://restore.test", "fixture", User("u"), "device")
    private val songs = (1..3).map { Item("song$it", "Song $it", ticks = 900_000_000) }

    private class Player : AudioPlayer {
        @Volatile var position = 0L
        @Volatile var duration = 0L
        @Volatile var paused = true
        val played = MutableStateFlow("")

        override suspend fun play(url: String, durationMs: Long) {
            position = 0
            duration = durationMs
            paused = false
            played.value = url
        }

        override fun pause(paused: Boolean) {
            this.paused = paused
        }

        override fun seek(positionMs: Long) {
            position = positionMs
        }

        override fun snapshot() = AudioSnapshot(position, duration, paused)

        override fun stop() {
            paused = true
        }

        override fun close() {
            stop()
        }
    }

    private class Library(@Volatile var saved: SavedPlayback?) : LocalMediaLibrary {
        override val revision = MutableStateFlow(0)
        val prefetched = MutableStateFlow<String?>(null)

        override suspend fun source(api: MusicRepository, session: Session, item: Item) =
            PlaybackSource("file:/offline/${item.id}", item.id)

        override suspend fun loadPlayback(session: Session) = saved

        override suspend fun savePlayback(session: Session, playback: SavedPlayback) {
            saved = playback
        }

        override suspend fun prefetch(api: MusicRepository, session: Session, item: Item) {
            prefetched.value = item.id
        }
    }

    private fun store() =
        object : SessionStore {
            override val deviceId = "device"

            override fun load() = session

            override fun save(session: Session?) {}
        }

    private fun api() =
        JellyfinApi(
            HttpClient(MockEngine { respond("offline", HttpStatusCode.ServiceUnavailable) }) {
                configureClient(this)
            }
        )

    @Test
    fun restoresPausedQueueModesAndProgressThenResumesTheSameLocalTrack() = runBlocking {
        val saved = SavedPlayback(songs, 1, 12345, true, true, true)
        val media = Library(saved)
        val player = Player()
        val api = api()
        val model =
            BellStore(
                api,
                store(),
                player,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                media,
            )
        try {
            val state = withTimeout(3000) { model.state.first { it.current?.id == "song2" } }
            assertTrue(state.audio.paused)
            assertTrue(state.shuffle && state.repeat && state.repeatOne)
            assertEquals(12345L, state.audio.positionMs)
            assertEquals(songs, state.queue)
            assertEquals("", player.played.value)
            delay(200)
            assertEquals(12345L, model.playback.value.positionMs)
            model.accept(BellIntent.Toggle)
            withTimeout(3000) { model.playback.first { it.positionMs == 12345L && !it.paused } }
            assertEquals("file:/offline/song2", player.played.value)
            model.persistPlayback()
            assertEquals(12345L, media.saved!!.positionMs)
        } finally {
            model.close()
            api.client.close()
        }
    }

    @Test
    fun nearTheEndPrefetchesTheActualNextShuffleChoice() = runBlocking {
        val media = Library(SavedPlayback(songs, 0, 0, true))
        val player = Player()
        val api = api()
        val model =
            BellStore(
                api,
                store(),
                player,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                media,
            )
        try {
            withTimeout(3000) { model.state.first { it.current != null } }
            model.accept(BellIntent.Toggle)
            withTimeout(3000) { player.played.first { it.isNotEmpty() } }
            player.position = 80_000
            val planned = withTimeout(3000) { media.prefetched.filterNotNull().first() }
            assertEquals(
                planned,
                model.state.value.queue[requireNotNull(model.miniSwipeIndex(true))].id,
            )
            model.accept(BellIntent.Next(true))
            assertEquals(planned, model.state.value.current!!.id)
        } finally {
            model.close()
            api.client.close()
        }
    }

    @Test
    fun swipePreviewDoesNotStartPlaybackAndIgnoresAutomaticRepeatOne() = runBlocking {
        val media = Library(SavedPlayback(songs, 1, 12345, repeat = true, repeatOne = true))
        val player = Player()
        val api = api()
        val model =
            BellStore(
                api,
                store(),
                player,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                media,
            )
        try {
            withTimeout(3000) { model.state.first { it.current?.id == "song2" } }
            assertEquals(2, model.miniSwipeIndex(true))
            assertEquals(0, model.miniSwipeIndex(false))
            assertEquals("song2", model.state.value.current?.id)
            assertEquals(12345L, model.playback.value.positionMs)
            assertEquals("", player.played.value)
        } finally {
            model.close()
            api.client.close()
        }
    }
}
