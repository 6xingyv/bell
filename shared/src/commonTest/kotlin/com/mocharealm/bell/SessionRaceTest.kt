// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SessionRaceTest {
    @Test
    fun logoutCancelsPendingLoginBeforeItCanRestoreSession() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client =
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    try {
                        response.await()
                    } catch (e: CancellationException) {
                        cancelled.complete(Unit)
                        throw e
                    }
                    respond(
                        """{"AccessToken":"token","User":{"Id":"user"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureClient(this)
            }
        val store =
            object : SessionStore {
                var session: Session? = null
                override val deviceId = "test"

                override fun load() = session

                override fun save(session: Session?) {
                    this.session = session
                }
            }
        val player =
            object : AudioPlayer {
                override suspend fun play(url: String, durationMs: Long) {}

                override fun pause(paused: Boolean) {}

                override fun seek(positionMs: Long) {}

                override fun snapshot() = AudioSnapshot()

                override fun stop() {}

                override fun close() {}
            }
        val model = BellStore(JellyfinApi(client), store, player, backgroundScope)
        model.accept(BellIntent.Login("http://localhost", "user", "password"))
        entered.await()
        model.accept(BellIntent.Logout)
        response.complete(Unit)
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertNull(model.state.value.session)
        assertNull(store.session)
        model.close()
        client.close()
    }
}
