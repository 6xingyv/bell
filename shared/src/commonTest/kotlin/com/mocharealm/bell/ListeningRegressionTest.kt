// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.*
import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.domain.playback.ListeningSession
import com.mocharealm.bell.domain.repository.MusicRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListeningRegressionTest {
    private val session = Session("http://server", "fixture-token", User("user"), "device")

    @Test
    fun largeQueuesUseBoundedMetadataRequests() = runTest {
        val sizes = mutableListOf<Int>()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertTrue(request.url.toString().length < 6000)
                    val ids = request.url.parameters["Ids"]!!.split(',')
                    sizes += ids.size
                    respond(
                        buildJsonObject {
                            put(
                                "Items",
                                JsonArray(
                                    ids.map { id ->
                                        buildJsonObject {
                                            put("Id", id)
                                            put("Name", id)
                                        }
                                    }
                                ),
                            )
                            put("TotalRecordCount", ids.size)
                        }
                            .toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureClient(this)
            }
        try {
            val ids = (0 until 453).map { it.toString().padStart(32, '0') }
            assertEquals(453, JellyfinApi(client).byIds(session, ids).size)
            assertEquals(listOf(80, 80, 80, 80, 80, 53), sizes)
        } finally {
            client.close()
        }
    }

    @Test
    fun queueLookupFailureDoesNotDisconnectOrLeaveGroup() = runTest {
        val events = MutableSharedFlow<JsonObject>()
        val requests = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        var attempts = 0
        val client =
            HttpClient(
                MockEngine {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) {
                configureClient(this)
            }
        val repository =
            object : MusicRepository by JellyfinApi(client) {
                override fun listeningEvents(s: Session) = events

                override suspend fun serverTime(s: Session) = buildJsonObject {
                    put("RequestReceptionTime", Clock.System.now().toString())
                    put("ResponseTransmissionTime", Clock.System.now().toString())
                }

                override suspend fun listeningRequest(
                    s: Session,
                    operation: String,
                    body: JsonObject,
                ): JsonObject {
                    requests += operation
                    return JsonObject(emptyMap())
                }
            }
        val listening =
            ListeningSession(
                repository,
                backgroundScope,
                { _, _, _, _ ->
                    attempts++
                    if (attempts == 1) error("metadata request failed")
                },
                { _, _ -> },
                { AudioSnapshot() },
                errors::add,
            )
        fun message(type: String, data: JsonObject) = buildJsonObject {
            put("MessageType", type)
            put("Data", data)
        }
        val group = buildJsonObject {
            put("GroupId", "group")
            put("GroupName", "Test")
            put("Participants", JsonArray(listOf(JsonPrimitive("Listener"))))
            put("State", "Waiting")
        }
        val update = buildJsonObject {
            put("GroupId", "group")
            put("Type", "PlayQueue")
            put(
                "Data",
                buildJsonObject {
                    put(
                        "Playlist",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("ItemId", "song")
                                    put("PlaylistItemId", "entry")
                                    put("AddedByUserId", "user")
                                    put("AddedByUserName", "Listener")
                                }
                            )
                        ),
                    )
                    put("PlayingItemIndex", 0)
                    put("StartPositionTicks", 0)
                    put("IsPlaying", false)
                    put("Reason", "NewPlaylist")
                },
            )
        }
        try {
            listening.connect(session)
            runCurrent()
            events.emit(buildJsonObject { put("MessageType", "BellConnected") })
            runCurrent()
            events.emit(
                message(
                    "SyncPlayGroupUpdate",
                    buildJsonObject {
                        put("GroupId", "group")
                        put("Type", "GroupJoined")
                        put("Data", group)
                    },
                )
            )
            runCurrent()
            events.emit(message("SyncPlayGroupUpdate", update))
            runCurrent()
            assertEquals(1, errors.size)
            assertTrue(listening.state.value.connected)
            assertEquals("group", listening.state.value.group?.id)
            events.emit(message("SyncPlayGroupUpdate", update))
            runCurrent()
            assertEquals(2, attempts)
            assertTrue("Ready" in requests)
            assertFalse("Leave" in requests)
            assertEquals("Listener", listening.state.value.entries.single().userName)
        } finally {
            listening.connect(null)
            client.close()
        }
    }
}
