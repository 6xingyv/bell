// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.repository

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.PlaylistInfo
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.UserProfile
import kotlinx.serialization.json.JsonObject

interface MusicRepository {
    suspend fun credits(s: Session, id: String): com.mocharealm.bell.domain.model.CreditDocument? =
        null

    suspend fun listeningUsers(s: Session): List<com.mocharealm.bell.domain.model.User> =
        emptyList()

    suspend fun listeningGroups(s: Session): List<com.mocharealm.bell.domain.model.ListeningGroup> =
        emptyList()

    suspend fun listeningRequest(
        s: Session,
        operation: String,
        body: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = JsonObject(emptyMap())

    fun listeningEvents(s: Session): kotlinx.coroutines.flow.Flow<JsonObject> =
        kotlinx.coroutines.flow.emptyFlow()

    suspend fun serverTime(s: Session): JsonObject = JsonObject(emptyMap())

    suspend fun login(server: String, username: String, password: String, deviceId: String): Session

    suspend fun items(
        s: Session,
        type: String,
        parent: String? = null,
        search: String? = null,
        favorites: Boolean = false,
    ): List<Item>

    suspend fun lyrics(s: Session, id: String): LyricDocument

    suspend fun playlistTracks(s: Session, id: String): List<Item>

    suspend fun query(
        s: Session,
        type: String,
        sort: String = "SortName",
        descending: Boolean = false,
        limit: Int = 24,
        extra: Map<String, String> = emptyMap(),
    ): List<Item>

    suspend fun recommendations(s: Session): List<Item>

    suspend fun genres(s: Session): List<Item>

    suspend fun mix(s: Session, seed: String): List<Item>

    suspend fun search(s: Session, term: String, favorites: Boolean): List<Item>

    suspend fun byIds(s: Session, ids: List<String>): List<Item>

    suspend fun item(s: Session, id: String): Item

    suspend fun artists(s: Session): List<Item>

    fun avatar(s: Session, tag: String?): String

    suspend fun profile(s: Session): UserProfile

    suspend fun playlistInfo(s: Session, id: String): PlaylistInfo

    suspend fun preferences(s: Session): JsonObject

    fun image(s: Session, item: Item, width: Int = 500): String

    suspend fun lyricDocument(s: Session, id: String): kotlinx.serialization.json.JsonObject

    suspend fun savePreferences(s: Session, values: Map<String, String>): Unit

    suspend fun suggestLess(s: Session, id: String): Unit

    suspend fun addToPlaylist(s: Session, id: String, ids: List<String>): Unit

    suspend fun renamePlaylist(s: Session, item: Item, name: String): Unit

    suspend fun deleteItem(s: Session, id: String): Unit

    suspend fun favorite(s: Session, item: Item, value: Boolean): Unit

    suspend fun report(s: Session, event: String, item: Item, position: Long, paused: Boolean): Unit

    fun stream(s: Session, id: String): String

    suspend fun createPlaylist(s: Session, name: String, ids: List<String>): String

    suspend fun itemDocument(s: Session, id: String): JsonObject

    suspend fun artworkBytes(source: String): ByteArray
}
