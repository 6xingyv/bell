// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.network

import com.mocharealm.bell.domain.model.Authentication
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.Items
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.MusicErrorKind
import com.mocharealm.bell.domain.model.PlaylistInfo
import com.mocharealm.bell.domain.model.SearchHints
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.UserProfile
import com.mocharealm.bell.domain.model.musicRequire
import com.mocharealm.bell.domain.repository.MusicRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

fun configureClient(config: HttpClientConfig<*>) =
    with(config) {
        expectSuccess = true
        install(ContentNegotiation) { json(wireJson) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

class JellyfinApi(val client: HttpClient, private val deviceName: () -> String = { "Bell" }) :
    MusicRepository {
    override suspend fun credits(
        s: Session,
        id: String,
    ): com.mocharealm.bell.domain.model.CreditDocument? =
        try {
            val response = client.get("${s.server}/AppleMusic/Credits/$id") { authenticate(s) }
            if (response.status == HttpStatusCode.NoContent) null else response.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null else throw e
        }

    override suspend fun listeningUsers(s: Session): List<com.mocharealm.bell.domain.model.User> =
        client.get("${s.server}/Users") { authenticate(s) }.body()

    override suspend fun listeningGroups(
        s: Session
    ): List<com.mocharealm.bell.domain.model.ListeningGroup> =
        client.get("${s.server}/SyncPlay/List") { authenticate(s) }.body()

    override suspend fun serverTime(s: Session): JsonObject =
        client.get("${s.server}/GetUtcTime") { authenticate(s) }.body()

    override suspend fun listeningRequest(
        s: Session,
        operation: String,
        body: JsonObject,
    ): JsonObject {
        val response =
            client.post("${s.server}/SyncPlay/$operation") {
                authenticate(s)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        return if (response.status == HttpStatusCode.NoContent) JsonObject(emptyMap())
        else response.body()
    }

    override fun listeningEvents(s: Session) = flow {
        val url =
            URLBuilder(s.server)
                .apply {
                    protocol =
                        if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
                    appendPathSegments("socket")
                    parameters.append("deviceId", s.deviceId)
                }
                .buildString()
        client.webSocket(urlString = url, request = { authenticate(s) }) {
            emit(buildJsonObject { put("MessageType", "BellConnected") })
            for (frame in incoming) if (frame is Frame.Text) {
                val message = wireJson.parseToJsonElement(frame.readText()).jsonObject
                if (message["MessageType"]?.jsonPrimitive?.content == "ForceKeepAlive")
                    send(Frame.Text("{\"MessageType\":\"KeepAlive\"}"))
                else emit(message)
            }
        }
    }

    override suspend fun artworkBytes(source: String) = readMusicArtwork(source, client)

    private fun auth(deviceId: String, token: String? = null) =
        "MediaBrowser Client=\"Bell\", Device=\"${deviceName().encodeURLParameter()}\", DeviceId=\"$deviceId\", Version=\"0.1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    fun normalizeServer(value: String): String {
        val url = Url(value.trim().let { if ("://" in it) it else "http://$it" })
        musicRequire(
            url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS,
            MusicErrorKind.InvalidServerScheme,
        )
        musicRequire(
            url.host.isNotBlank() && url.user.isNullOrBlank() && url.password.isNullOrBlank(),
            MusicErrorKind.InvalidServer,
        )
        return url.toString().trimEnd('/')
    }

    override suspend fun login(
        server: String,
        username: String,
        password: String,
        deviceId: String,
    ): Session {
        val base = normalizeServer(server)
        val result =
            client
                .post("$base/Users/AuthenticateByName") {
                    header(HttpHeaders.Authorization, auth(deviceId))
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("Username", username)
                            put("Pw", password)
                        }
                    )
                }
                .body<Authentication>()
        return Session(base, result.token, result.user, deviceId)
    }

    private fun HttpRequestBuilder.authenticate(s: Session) {
        header(HttpHeaders.Authorization, auth(s.deviceId, s.token))
    }

    override suspend fun items(
        s: Session,
        type: String,
        parent: String?,
        search: String?,
        favorites: Boolean,
    ): List<Item> {
        val result = mutableListOf<Item>()
        do {
            val page =
                client
                    .get("${s.server}/Items") {
                        authenticate(s)
                        parameter("UserId", s.user.id)
                        parameter("IncludeItemTypes", type)
                        parameter("Recursive", true)
                        parameter(
                            "Fields",
                            "Genres,DateCreated,CanDelete,Overview,PrimaryImageAspectRatio",
                        )
                        parameter(
                            "SortBy",
                            if (parent != null) "ParentIndexNumber,IndexNumber,SortName"
                            else "SortName",
                        )
                        parameter("SortOrder", "Ascending")
                        parameter("StartIndex", result.size)
                        parameter("Limit", 200)
                        parent?.let { parameter("ParentId", it) }
                        search?.takeIf { it.isNotBlank() }?.let { parameter("SearchTerm", it) }
                        if (favorites) parameter("Filters", "IsFavorite")
                    }
                    .body<Items>()
            result += page.items
        } while (page.items.isNotEmpty() && result.size < page.total)
        return result
    }

    override suspend fun lyrics(s: Session, id: String): LyricDocument =
        try {
            client.get("${s.server}/Audio/$id/Lyrics") { authenticate(s) }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) LyricDocument() else throw e
        }

    override suspend fun playlistTracks(s: Session, id: String): List<Item> =
        client
            .get("${s.server}/Playlists/$id/Items") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter("Fields", "PrimaryImageAspectRatio")
            }
            .body<Items>()
            .items

    override suspend fun query(
        s: Session,
        type: String,
        sort: String,
        descending: Boolean,
        limit: Int,
        extra: Map<String, String>,
    ): List<Item> =
        client
            .get("${s.server}/Items") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter("Recursive", true)
                parameter("IncludeItemTypes", type)
                parameter("SortBy", sort)
                parameter("SortOrder", if (descending) "Descending" else "Ascending")
                parameter("Limit", limit)
                parameter("Fields", "Genres,DateCreated,CanDelete,Overview,PrimaryImageAspectRatio")
                extra.forEach { (key, value) -> parameter(key, value) }
            }
            .body<Items>()
            .items

    override suspend fun recommendations(s: Session): List<Item> =
        client
            .get("${s.server}/Items/Suggestions") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter("Type", "MusicAlbum")
                parameter("Limit", 16)
                parameter("Fields", "PrimaryImageAspectRatio")
            }
            .body<Items>()
            .items

    override suspend fun genres(s: Session): List<Item> {
        val result = mutableListOf<Item>()
        do {
            val page =
                client
                    .get("${s.server}/MusicGenres") {
                        authenticate(s)
                        parameter("UserId", s.user.id)
                        parameter("Fields", "PrimaryImageAspectRatio")
                        parameter("Limit", 200)
                        parameter("StartIndex", result.size)
                        parameter("EnableTotalRecordCount", true)
                        parameter("SortBy", "SortName")
                        parameter("EnableImages", true)
                    }
                    .body<Items>()
            result += page.items
        } while (page.items.isNotEmpty() && result.size < page.total)
        return result.distinctBy { it.id }
    }

    override suspend fun mix(s: Session, seed: String): List<Item> =
        client
            .get("${s.server}/Items/$seed/InstantMix") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter("Limit", 30)
                parameter("Fields", "Genres,CanDelete,PrimaryImageAspectRatio")
            }
            .body<Items>()
            .items

    override suspend fun search(s: Session, term: String, favorites: Boolean): List<Item> {
        if (favorites)
            return query(
                s,
                "Audio,MusicAlbum,MusicArtist,Playlist",
                limit = 80,
                extra = mapOf("SearchTerm" to term, "Filters" to "IsFavorite"),
            )
        val hints =
            client
                .get("${s.server}/Search/Hints") {
                    authenticate(s)
                    parameter("UserId", s.user.id)
                    parameter("SearchTerm", term)
                    parameter(
                        "IncludeItemTypes",
                        "Audio,MusicAlbum,MusicArtist,Playlist,MusicGenre",
                    )
                    parameter("IncludePeople", false)
                    parameter("IncludeStudios", false)
                    parameter("IncludeArtists", true)
                    parameter("IncludeGenres", true)
                    parameter("Limit", 80)
                }
                .body<SearchHints>()
                .items
        val items = byIds(s, hints.map { it.id }).associateBy { it.id }
        return hints.map { items[it.id] ?: it }
    }

    override suspend fun byIds(s: Session, ids: List<String>): List<Item> =
        ids.distinct().chunked(80).flatMap { batch ->
            query(
                s,
                "MusicAlbum,Audio,Playlist,MusicArtist,MusicGenre",
                limit = batch.size,
                extra = mapOf("Ids" to batch.joinToString(",")),
            )
        }

    override suspend fun item(s: Session, id: String): Item =
        client
            .get("${s.server}/Items/$id") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter(
                    "Fields",
                    "People,Genres,CanDelete,DateCreated,SortName,Overview,PrimaryImageAspectRatio",
                )
            }
            .body()

    override suspend fun artists(s: Session): List<Item> {
        val result = mutableListOf<Item>()
        do {
            val page =
                client
                    .get("${s.server}/Artists") {
                        authenticate(s)
                        parameter("UserId", s.user.id)
                        parameter("SortBy", "SortName")
                        parameter("SortOrder", "Ascending")
                        parameter("Fields", "SortName,Genres,PrimaryImageAspectRatio")
                        parameter("StartIndex", result.size)
                        parameter("Limit", 200)
                        parameter("EnableImages", true)
                        parameter("EnableUserData", true)
                    }
                    .body<Items>()
            result += page.items.map { it.copy(type = "MusicArtist") }
        } while (page.items.isNotEmpty() && result.size < page.total)
        return result
    }

    override fun avatar(s: Session, tag: String?): String =
        URLBuilder("${s.server}/UserImage")
            .apply {
                parameters.append("userId", s.user.id)
                parameters.append("api_key", s.token)
                tag?.let { parameters.append("tag", it) }
            }
            .buildString()

    override suspend fun profile(s: Session): UserProfile =
        client.get("${s.server}/Users/${s.user.id}") { authenticate(s) }.body()

    override suspend fun playlistInfo(s: Session, id: String): PlaylistInfo =
        client.get("${s.server}/Playlists/$id/Users/${s.user.id}") { authenticate(s) }.body()

    override suspend fun preferences(s: Session): JsonObject =
        client
            .get("${s.server}/DisplayPreferences/jellify.music") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter("Client", "Jellify")
            }
            .body()

    override suspend fun savePreferences(s: Session, values: Map<String, String>) {
        val original = preferences(s)
        val old = original["CustomPrefs"] as? JsonObject ?: JsonObject(emptyMap())
        val custom =
            old.toMutableMap().apply {
                values.forEach { (key, value) -> this[key] = JsonPrimitive(value) }
            }
        client.post("${s.server}/DisplayPreferences/jellify.music") {
            authenticate(s)
            parameter("UserId", s.user.id)
            parameter("Client", "Jellify")
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    original.toMutableMap().apply { this["CustomPrefs"] = JsonObject(custom) }
                )
            )
        }
    }

    override suspend fun suggestLess(s: Session, id: String) {
        client.post("${s.server}/UserItems/$id/Rating") {
            authenticate(s)
            parameter("UserId", s.user.id)
            parameter("Likes", false)
        }
    }

    override suspend fun addToPlaylist(s: Session, id: String, ids: List<String>) {
        client.post("${s.server}/Playlists/$id/Items") {
            authenticate(s)
            parameter("UserId", s.user.id)
            parameter("Ids", ids.joinToString(","))
        }
    }

    override suspend fun createPlaylist(s: Session, name: String, ids: List<String>): String {
        val result =
            client
                .post("${s.server}/Playlists") {
                    authenticate(s)
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("Name", name)
                            put("UserId", s.user.id)
                            put("MediaType", "Audio")
                            put("IsPublic", false)
                            put("Ids", JsonArray(ids.map(::JsonPrimitive)))
                        }
                    )
                }
                .body<JsonObject>()
        return result["Id"]!!.jsonPrimitive.content
    }

    override suspend fun renamePlaylist(s: Session, item: Item, name: String) {
        client.post("${s.server}/Playlists/${item.id}") {
            authenticate(s)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("Name", name) })
        }
    }

    override suspend fun deleteItem(s: Session, id: String) {
        client.delete("${s.server}/Items/$id") { authenticate(s) }
    }

    override fun stream(s: Session, id: String): String =
        URLBuilder("${s.server}/Audio/$id/stream")
            .apply {
                parameters.append("static", "true")
                parameters.append("api_key", s.token)
                parameters.append("deviceId", s.deviceId)
            }
            .buildString()

    override fun image(s: Session, item: Item, width: Int): String =
        URLBuilder(
                "${s.server}/Items/${if (item.type == "Audio" && item.imageTags["Primary"] == null) item.albumId ?: item.id else item.id}/Images/Primary"
            )
            .apply {
                parameters.append("maxWidth", width.toString())
                parameters.append("quality", "90")
                parameters.append("api_key", s.token)
            }
            .buildString()

    override suspend fun favorite(s: Session, item: Item, value: Boolean) {
        val path = "${s.server}/Users/${s.user.id}/FavoriteItems/${item.id}"
        if (value) client.post(path) { authenticate(s) }
        else client.delete(path) { authenticate(s) }
    }

    override suspend fun report(
        s: Session,
        event: String,
        item: Item,
        position: Long,
        paused: Boolean,
    ) {
        client.post("${s.server}/Sessions/Playing$event") {
            authenticate(s)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("ItemId", item.id)
                    put("PositionTicks", position * 10_000)
                    put("IsPaused", paused)
                    put("CanSeek", true)
                    put("PlayMethod", "DirectStream")
                }
            )
        }
    }

    // Keep unmodeled server fields too, so offline metadata is not reduced to the UI projection.
    override suspend fun itemDocument(
        s: Session,
        id: String,
    ): kotlinx.serialization.json.JsonObject =
        client
            .get("${s.server}/Items/$id") {
                authenticate(s)
                parameter("UserId", s.user.id)
                parameter(
                    "Fields",
                    "People,Genres,CanDelete,DateCreated,SortName,Overview,MediaSources,MediaStreams,ProviderIds,Tags,Studios,ExternalUrls,PrimaryImageAspectRatio",
                )
            }
            .body()

    override suspend fun lyricDocument(
        s: Session,
        id: String,
    ): kotlinx.serialization.json.JsonObject =
        try {
            client.get("${s.server}/Audio/$id/Lyrics") { authenticate(s) }.body()
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound)
                kotlinx.serialization.json.JsonObject(emptyMap())
            else throw e
        }
}
