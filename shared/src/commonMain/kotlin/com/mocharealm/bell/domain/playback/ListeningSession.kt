// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.playback

import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.domain.repository.MusicRepository
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Jellyfin SyncPlay's server-authoritative queue and scheduled playback protocol. */
class ListeningSession(
    private val repository: MusicRepository,
    private val scope: CoroutineScope,
    private val queue: suspend (List<ListeningEntry>, Int, Long, Boolean) -> Unit,
    private val command: suspend (String, Long?) -> Unit,
    private val snapshot: () -> AudioSnapshot,
    private val error: (Exception) -> Unit,
    private val libraryChanged: (Boolean) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(ListeningState())
    val state = mutable.asStateFlow()
    private var session: Session? = null
    private var connection: Job? = null
    private var scheduled: Job? = null
    private var serverOffset = 0L
    private var loaded: String? = null
    private var commandStamp = Long.MIN_VALUE
    private var anchor: Pair<Long, Long>? = null
    private var queueStamp = Long.MIN_VALUE

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun ticks(ms: Long) = JsonPrimitive(ms * 10_000)

    fun connect(value: Session?) {
        connection?.cancel()
        scheduled?.cancel()
        session = value
        loaded = null
        anchor = null
        commandStamp = Long.MIN_VALUE
        queueStamp = Long.MIN_VALUE
        mutable.value = ListeningState()
        if (value == null) return
        connection = scope.launch {
            while (isActive) {
                try {
                    repository.listeningEvents(value).collect { message ->
                        try {
                            when (message.text("MessageType")) {
                                "BellConnected" -> {
                                    mutable.update { it.copy(connected = true) }
                                    calibrate(value)
                                    // Rejoin after a socket disconnect to receive a fresh queue
                                    // snapshot.
                                    mutable.value.group?.let {
                                        request("Join", buildJsonObject { put("GroupId", it.id) })
                                    }
                                }
                                "LibraryChanged" -> libraryChanged(true)
                                "UserDataChanged" -> libraryChanged(false)
                                "SyncPlayGroupUpdate" ->
                                    update(message["Data"]?.jsonObject ?: return@collect)
                                "SyncPlayCommand" ->
                                    schedule(message["Data"]?.jsonObject ?: return@collect)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("BellSync message failure: ${e.stackTraceToString()}")
                            error(e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("BellSync connection failure: ${e.stackTraceToString()}")
                    if (mutable.value.active) error(e)
                }
                mutable.update { it.copy(connected = false) }
                scheduled?.cancel()
                anchor = null
                if (mutable.value.active) command("Pause", null)
                delay(2000)
            }
        }
    }

    private suspend fun calibrate(s: Session) {
        var best = Long.MAX_VALUE
        repeat(3) {
            val before = now()
            val result = repository.serverTime(s)
            val after = now()
            val received =
                result.text("RequestReceptionTime")?.let { Instant.parse(it).toEpochMilliseconds() }
            val sent =
                result.text("ResponseTransmissionTime")?.let {
                    Instant.parse(it).toEpochMilliseconds()
                }
            if (received != null && sent != null && after - before < best) {
                best = after - before
                serverOffset = ((received - before) + (sent - after)) / 2
            }
        }
        if (mutable.value.active)
            request("Ping", buildJsonObject { put("Ping", best.coerceAtMost(1000)) })
    }

    suspend fun refreshGroups() {
        val s = session ?: return
        val groups = repository.listeningGroups(s)
        if (session != s) return
        mutable.update {
            it.copy(groups = groups, group = groups.find { g -> g.id == it.group?.id } ?: it.group)
        }
        refreshMembers()
    }

    private suspend fun refreshMembers() {
        val s = session ?: return
        val groupId = mutable.value.group?.id ?: return
        val users =
            try {
                repository.listeningUsers(s)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        if (session != s) return
        mutable.update { current ->
            if (current.group?.id != groupId) current
            else
                current.copy(
                    members =
                        (users + s.user)
                            .distinctBy { it.id }
                            .filter { it.name in current.group.participants }
                )
        }
    }

    suspend fun create(name: String) {
        val data = request("New", buildJsonObject { put("GroupName", name) })
        if (data["GroupId"] != null) {
            mutable.update { it.copy(group = decodeGroup(data)) }
            refreshMembers()
        }
    }

    suspend fun join(id: String) {
        request("Join", buildJsonObject { put("GroupId", id) })
    }

    suspend fun leave() {
        request("Leave")
        reset()
    }

    private fun reset() {
        scheduled?.cancel()
        loaded = null
        anchor = null
        mutable.update { it.copy(group = null, members = emptyList(), entries = emptyList()) }
    }

    suspend fun request(operation: String, body: JsonObject = JsonObject(emptyMap())): JsonObject =
        repository.listeningRequest(session ?: return JsonObject(emptyMap()), operation, body)

    private fun decodeGroup(data: JsonObject) = json.decodeFromJsonElement<ListeningGroup>(data)

    private suspend fun update(event: JsonObject) {
        val data = event["Data"]
        when (event.text("Type")) {
            "GroupJoined" -> {
                mutable.update { it.copy(group = decodeGroup(data!!.jsonObject)) }
                loaded = null
                queueStamp = Long.MIN_VALUE
                refreshMembers()
            }
            "UserJoined",
            "UserLeft" -> refreshGroups()
            "GroupLeft",
            "NotInGroup" -> reset()
            "GroupDoesNotExist",
            "LibraryAccessDenied" -> {
                reset()
                error(IllegalStateException(event.text("Type")))
            }
            "PlayQueue" -> {
                val groupId = event.text("GroupId")
                if (groupId != mutable.value.group?.id) return
                val q = data!!.jsonObject
                val stamp =
                    q.text("LastUpdate")?.let { Instant.parse(it).toEpochMilliseconds() } ?: now()
                if (stamp < queueStamp) return
                queueStamp = stamp
                val entries =
                    q["Playlist"]!!.jsonArray.map { json.decodeFromJsonElement<ListeningEntry>(it) }
                val index = q["PlayingItemIndex"]!!.jsonPrimitive.int
                val position = (q["StartPositionTicks"]?.jsonPrimitive?.longOrNull ?: 0) / 10_000
                val playing = q["IsPlaying"]?.jsonPrimitive?.booleanOrNull ?: false
                val changed =
                    loaded != entries.getOrNull(index)?.id ||
                        q.text("Reason") in listOf("NewPlaylist", "SetCurrentItem")
                mutable.update {
                    it.copy(
                        entries = entries,
                        shuffle = q.text("ShuffleMode") == "Shuffle",
                        repeatMode = q.text("RepeatMode") ?: "RepeatNone",
                    )
                }
                queue(entries, index, position, changed)
                if (changed && entries.getOrNull(index) != null) {
                    loaded = entries[index].id
                    anchor = null
                    ready(position, false)
                }
            }
        }
    }

    private fun schedule(data: JsonObject) {
        if (data.text("GroupId") != mutable.value.group?.id) return
        if (data.text("PlaylistItemId") != loaded) return
        val emitted =
            data.text("EmittedAt")?.let { Instant.parse(it).toEpochMilliseconds() } ?: now()
        if (emitted < commandStamp) return
        commandStamp = emitted
        scheduled?.cancel()
        scheduled = scope.launch {
            val whenAt =
                data.text("When")?.let { Instant.parse(it).toEpochMilliseconds() }
                    ?: now() + serverOffset
            val target = (data["PositionTicks"]?.jsonPrimitive?.longOrNull)?.div(10_000)
            val operation = data.text("Command") ?: return@launch
            delay((whenAt - now() - serverOffset).coerceAtLeast(0))
            val late = (now() + serverOffset - whenAt).coerceAtLeast(0)
            command(operation, target?.plus(if (operation == "Unpause") late else 0))
            if (operation == "Seek") ready(target ?: snapshot().positionMs, false)
            anchor =
                if (operation == "Unpause") whenAt to (target ?: snapshot().positionMs) else null
            while (isActive && anchor != null) {
                delay(2000)
                val sample = snapshot()
                val (at, position) = anchor ?: break
                if (!sample.buffering && !sample.paused && !sample.ended) {
                    val expected = position + now() + serverOffset - at
                    if (abs(sample.positionMs - expected) > 300) command("Correct", expected)
                }
            }
        }
    }

    suspend fun ready(
        position: Long = snapshot().positionMs,
        playing: Boolean = !snapshot().paused,
    ) {
        val id = loaded ?: mutable.value.entries.firstOrNull()?.id ?: return
        request(
            "Ready",
            buildJsonObject {
                put("When", Instant.fromEpochMilliseconds(now() + serverOffset).toString())
                put("PositionTicks", ticks(position))
                put("IsPlaying", playing)
                put("PlaylistItemId", id)
            },
        )
    }

    suspend fun buffering() {
        val id = loaded ?: return
        anchor = null
        request(
            "Buffering",
            buildJsonObject {
                put("When", Instant.fromEpochMilliseconds(now() + serverOffset).toString())
                put("PositionTicks", ticks(snapshot().positionMs))
                put("IsPlaying", !snapshot().paused)
                put("PlaylistItemId", id)
            },
        )
    }
}
