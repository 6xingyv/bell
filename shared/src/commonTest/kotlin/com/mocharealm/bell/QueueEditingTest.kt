// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.domain.repository.*
import com.mocharealm.bell.ui.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueEditingTest {
    private val songs =
        (0..5).map { Item("song$it", "Song $it", album = "Album", ticks = 100_000L * 10_000) }
    private val session = Session("http://offline", "fixture-token", User("listener"), "device")

    private class Player : AudioPlayer {
        var plays = 0

        override suspend fun play(url: String, durationMs: Long) {
            plays++
        }

        override fun pause(paused: Boolean) {}

        override fun seek(positionMs: Long) {}

        override fun snapshot() = AudioSnapshot()

        override fun stop() {}

        override fun close() {}
    }

    private class Library(var saved: SavedPlayback) : LocalMediaLibrary {
        override val revision = MutableStateFlow(0)

        override suspend fun loadPlayback(session: Session) = saved

        override suspend fun savePlayback(session: Session, playback: SavedPlayback) {
            saved = playback
        }

        override suspend fun source(api: MusicRepository, session: Session, item: Item) =
            PlaybackSource("file:/${item.id}", item.id)
    }

    private suspend fun TestScope.fixture(block: suspend (BellStore, Library, Player) -> Unit) {
        val client =
            HttpClient(MockEngine { respond("offline", HttpStatusCode.ServiceUnavailable) }) {
                configureClient(this)
            }
        val library = Library(SavedPlayback(songs, 1, 9_000))
        val player = Player()
        val store =
            object : SessionStore {
                override val deviceId = "device"

                override fun load() = session

                override fun save(session: Session?) {}
            }
        val model = BellStore(JellyfinApi(client), store, player, backgroundScope, library)
        try {
            runCurrent()
            block(model, library, player)
        } finally {
            model.close()
            client.close()
        }
    }

    @Test
    fun movingUpcomingSongsPreservesTheCurrentOccurrenceAndProgress() = runTest {
        fixture { model, _, player ->
            val before = model.state.value
            model.accept(BellIntent.MoveQueue(before.queueKeys[2], before.queueKeys[5]))
            val after = model.state.value
            assertEquals(
                listOf("song0", "song1", "song3", "song4", "song5", "song2"),
                after.queue.map { it.id },
            )
            assertEquals(before.queueKeys[1], after.queueKeys[after.queueIndex])
            assertEquals(before.audio, after.audio)
            assertEquals(0, player.plays)
        }
    }

    @Test
    fun duplicateSongsHaveDistinctReorderIdentities() = runTest {
        fixture { model, _, _ ->
            model.accept(BellIntent.EnqueueMany(listOf(songs[3], songs[3])))
            val before = model.state.value
            val keys = before.queueKeys.slice(2..3)
            assertNotEquals(keys[0], keys[1])
            model.accept(BellIntent.MoveQueue(keys[0], keys[1]))
            assertEquals(keys.reversed(), model.state.value.queueKeys.slice(2..3))
            assertEquals(2, model.state.value.manualQueueCount)
        }
    }

    @Test
    fun clearingManualQueueRetainsAlbumContinuation() = runTest {
        fixture { model, _, player ->
            model.accept(BellIntent.EnqueueMany(listOf(Item("manual1"), Item("manual2"))))
            assertEquals(
                listOf("manual1", "manual2"),
                model.state.value.queue.slice(2..3).map { it.id },
            )
            model.accept(BellIntent.ClearQueue)
            assertEquals(songs, model.state.value.queue)
            assertEquals(0, model.state.value.manualQueueCount)
            assertEquals(9_000L, model.state.value.audio.positionMs)
            assertEquals(0, player.plays)
        }
    }

    @Test
    fun clearingHistoryDoesNotDeleteUpcomingOrRestartAudio() = runTest {
        fixture { model, library, player ->
            val before = model.state.value
            assertTrue(before.history.isNotEmpty())
            model.accept(BellIntent.ClearHistory)
            model.persistPlayback()
            assertTrue(model.state.value.history.isEmpty())
            assertEquals(before.queueKeys, model.state.value.queueKeys)
            assertEquals(before.audio, model.state.value.audio)
            assertEquals(emptyList(), library.saved.history)
            assertEquals(0, player.plays)
        }
    }

    @Test
    fun shuffleChangesDisplayedPlaybackOrderAndRestoresItWithoutMovingCurrent() = runTest {
        fixture { model, _, player ->
            val before = model.state.value
            model.accept(BellIntent.Shuffle)
            val shuffled = model.state.value
            assertNotEquals(before.queue.drop(2), shuffled.queue.drop(2))
            assertEquals(before.queue.take(2), shuffled.queue.take(2))
            assertEquals(2, model.miniSwipeIndex(true))
            model.accept(BellIntent.Shuffle)
            assertEquals(before.queue, model.state.value.queue)
            assertEquals(before.queueKeys, model.state.value.queueKeys)
            assertEquals(0, player.plays)
        }
    }
}
