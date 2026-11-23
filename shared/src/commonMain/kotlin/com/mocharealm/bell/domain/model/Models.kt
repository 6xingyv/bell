// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.model

import com.mocharealm.bell.domain.playback.monotonicMillis
import com.mocharealm.bell.domain.repository.PlaybackSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

@Serializable
data class Authentication(
    @SerialName("AccessToken") val token: String,
    @SerialName("User") val user: User,
)

@Serializable
data class Session(val server: String, val token: String, val user: User, val deviceId: String)

@Serializable
data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "Audio",
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("RunTimeTicks") val ticks: Long = 0,
    @SerialName("IndexNumber") val number: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("DateCreated") val created: String? = null,
    @SerialName("PremiereDate") val released: String? = null,
    @SerialName("CanDelete") val canDelete: Boolean? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("SortName") val sortName: String? = null,
    @SerialName("ArtistItems") val artistItems: List<NamedItem> = emptyList(),
    @SerialName("People") val people: List<CreditPerson> = emptyList(),
    @SerialName("AlbumArtists") val albumArtists: List<NamedItem> = emptyList(),
    @SerialName("ParentIndexNumber") val discNumber: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("UserData") val userData: UserData = UserData(),
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Float? = null,
) {
    val durationMs
        get() = ticks / 10_000

    val albumArtistNames
        get() =
            albumArtists
                .map { it.name }
                .filter { it.isNotBlank() }
                .ifEmpty { listOfNotNull(albumArtist?.takeIf { it.isNotBlank() }) }
                .ifEmpty { artists.filter { it.isNotBlank() } }

    val artist
        get() =
            if (type == "MusicAlbum")
                albumArtistNames.let { names ->
                    if (names.size > 1) names.dropLast(1).joinToString(", ") + " & " + names.last()
                    else names.firstOrNull().orEmpty()
                }
            else artists.joinToString(" / ").ifBlank { albumArtist.orEmpty() }

    val genre
        get() = genres.firstOrNull()
}

@Serializable
data class NamedItem(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

@Serializable
data class CreditPerson(
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("PrimaryImageTag") val imageTag: String? = null,
)

@Serializable
data class UserData(
    @SerialName("IsFavorite") val favorite: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayed: String? = null,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("Likes") val likes: Boolean? = null,
)

@Serializable data class SearchHints(@SerialName("SearchHints") val items: List<Item> = emptyList())

@Serializable
data class PlaylistInfo(
    @SerialName("Name") val name: String = "",
    @SerialName("OwnerUserId") val owner: String = "",
    @SerialName("CanEdit") val canEdit: Boolean = false,
    @SerialName("IsPublic") val public: Boolean = false,
)

@Serializable
data class UserPolicy(
    @SerialName("EnableContentDeletion") val delete: Boolean = false,
    @SerialName("IsAdministrator") val admin: Boolean = false,
)

@Serializable
data class UserProfile(
    @SerialName("Policy") val policy: UserPolicy = UserPolicy(),
    @SerialName("PrimaryImageTag") val imageTag: String? = null,
)

data class MusicMix(val seed: Item, val tracks: List<Item>)

enum class SearchScope {
    Library,
    Favorites,
}

@Serializable
data class Items(
    @SerialName("Items") val items: List<Item> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable
data class LyricDocument(
    @SerialName("Tracks") val tracks: List<LyricTrack> = emptyList(),
    @SerialName("Lyrics") val legacy: List<LyricLine> = emptyList(),
    @SerialName("Metadata") val metadata: LyricMetadata = LyricMetadata(),
)

@Serializable
data class LyricMetadata(@SerialName("Artists") val artists: List<LyricArtist> = emptyList())

@Serializable
data class LyricArtist(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class LyricTrack(
    @SerialName("Type") val type: String = "Main",
    @SerialName("Language") val language: String? = null,
    @SerialName("Lines") val lines: List<LyricLine> = emptyList(),
)

@Serializable
data class LyricLine(
    @SerialName("Text") val text: String = "",
    @SerialName("Start") val start: Long? = null,
    @SerialName("End") val end: Long? = null,
    @SerialName("Syllables") val syllables: List<LyricSyllable> = emptyList(),
    @SerialName("ArtistIds") val artistIds: List<String> = emptyList(),
    @SerialName("SourceKey") val sourceKey: String? = null,
    @SerialName("ParentKey") val parentKey: String? = null,
)

@Serializable
data class LyricSyllable(
    @SerialName("Text") val text: String = "",
    @SerialName("Start") val start: Long? = null,
    @SerialName("End") val end: Long? = null,
    @SerialName("Phonetic") val phonetic: String? = null,
)

enum class LyricKind {
    Karaoke,
    Synced,
    Unsynced,
}

data class AudioSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = true,
    val sampledAt: Long = monotonicMillis(),
    val revision: Long = 0,
    val ended: Boolean = false,
    val transitionCount: Long = 0,
    val nativeEntryKey: Long? = null,
    val buffering: Boolean = false,
    val failed: Boolean = false,
)

interface AudioPlayer {
    suspend fun play(url: String, durationMs: Long)

    suspend fun play(source: PlaybackSource, durationMs: Long) = play(source.url, durationMs)

    fun setAutoMix(enabled: Boolean) {}

    suspend fun prepareTransition(source: PlaybackSource, key: Long, durationMs: Long) {}

    fun cancelTransition() {}

    fun pause(paused: Boolean)

    fun seek(positionMs: Long)

    fun readSpectrumPower(output: FloatArray): Boolean {
        output.fill(0f)
        return false
    }

    fun snapshot(): AudioSnapshot

    fun stop()

    fun close()
}

interface SessionStore {
    fun load(): Session?

    fun save(session: Session?)

    val deviceId: String
}
