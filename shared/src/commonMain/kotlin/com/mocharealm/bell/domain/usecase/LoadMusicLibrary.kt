// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.usecase

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.MusicMix
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.UserProfile
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import com.mocharealm.bell.domain.repository.SavedLibrary
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class MusicLibrarySnapshot(
    val albums: List<Item>,
    val songs: List<Item>,
    val playlists: List<Item>,
    val latest: List<Item>,
    val recentlyPlayed: List<Item>,
    val recommendations: List<Item>,
    val genres: List<Item>,
    val pins: List<Item>,
    val searchHistory: List<Item>,
    val profile: UserProfile,
    val artists: List<Item>,
    val mixSeeds: List<Item>,
)

class LoadMusicLibrary(
    private val repository: MusicRepository,
    private val media: LocalMediaLibrary,
) {
    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    suspend fun load(session: Session): MusicLibrarySnapshot = coroutineScope {
        val albums = async { repository.items(session, "MusicAlbum") }
        val songs = async { repository.items(session, "Audio") }
        val playlists = async { repository.items(session, "Playlist") }
        val latest = async {
            optional(emptyList<Item>()) {
                repository.query(session, "MusicAlbum", "DateCreated", true)
            }
        }
        val recent = async {
            optional(emptyList<Item>()) {
                repository.query(session, "Audio", "DatePlayed", true, 120).filter {
                    it.userData.lastPlayed != null || it.userData.playCount > 0
                }
            }
        }
        val picks = async { optional(emptyList<Item>()) { repository.recommendations(session) } }
        val genres = async { optional(emptyList<Item>()) { repository.genres(session) } }
        val preferences = async {
            optional(JsonObject(emptyMap())) { repository.preferences(session) }
        }
        val profile = async { optional(UserProfile()) { repository.profile(session) } }
        val artists = async { optional(emptyList<Item>()) { repository.artists(session) } }
        val a = albums.await()
        val s = songs.await()
        val p = playlists.await()
        val albumIndex = a.associateBy { it.id }
        fun List<Item>.asAlbums() = mapNotNull { item ->
            when (item.type) {
                "MusicAlbum" -> albumIndex[item.id] ?: item
                "Audio" -> item.albumId?.let(albumIndex::get)
                else -> null
            }
        }
            .distinctBy { it.id }
        val l = latest.await().asAlbums().ifEmpty { a.sortedByDescending { it.created }.take(24) }
        val r = recent.await()
        val g = genres.await()
        val rec = picks.await().filter { it.userData.likes != false }
        val prefs = preferences.await()["CustomPrefs"] as? JsonObject
        suspend fun savedItems(key: String): List<Item> {
            val ids =
                optional(emptyList<String>()) {
                    Json.parseToJsonElement(prefs?.get(key)?.jsonPrimitive?.content ?: "[]")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                }
            val found =
                optional(emptyList<Item>()) { repository.byIds(session, ids) }.associateBy { it.id }
            return ids.mapNotNull { found[it] }
        }
        val pins = savedItems("pins")
        val history = savedItems("searchHistory")
        val userProfile = profile.await()
        val ar = artists.await()

        media.saveLibrary(session, SavedLibrary(a, s, p, ar, pins, l))
        MusicLibrarySnapshot(
            a,
            s,
            p,
            l,
            r.asAlbums().take(24),
            rec.asAlbums(),
            g,
            pins,
            history,
            userProfile,
            ar,
            (r + s.filter { it.userData.favorite }).distinctBy { it.id }.take(3),
        )
    }

    suspend fun mixes(session: Session, seeds: List<Item>): List<MusicMix> = coroutineScope {
        seeds
            .map { seed ->
                async {
                    optional<MusicMix?>(null) {
                        repository
                            .mix(session, seed.id)
                            .takeIf { it.isNotEmpty() }
                            ?.let { MusicMix(seed, it) }
                    }
                }
            }
            .mapNotNull { it.await() }
    }
}
