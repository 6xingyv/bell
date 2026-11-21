// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.repository

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.PlaybackHistoryEntry
import com.mocharealm.bell.domain.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedPlayback(
    val queue: List<Item>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val repeatOne: Boolean = false,
    val capturedAt: Long = 0,
    val queueKeys: List<Long> = emptyList(),
    val history: List<PlaybackHistoryEntry>? = null,
    val manualQueueCount: Int = 0,
    val context: String? = null,
    val autoPlay: Boolean = false,
    val autoMix: Boolean = false,
    val unshuffledKeys: List<Long> = emptyList(),
)

@Serializable
data class SavedLibrary(
    val albums: List<Item> = emptyList(),
    val songs: List<Item> = emptyList(),
    val playlists: List<Item> = emptyList(),
    val artists: List<Item> = emptyList(),
    val pins: List<Item> = emptyList(),
    val latest: List<Item> = emptyList(),
)

data class PlaybackSource(val url: String, val cacheKey: String)

interface LocalMediaLibrary {
    val downloadProgress: StateFlow<Map<String, Float>>
        get() = NoDownloadProgress

    suspend fun cancelDownload(session: Session, item: Item) {}

    val revision: StateFlow<Int>

    suspend fun source(api: MusicRepository, session: Session, item: Item): PlaybackSource

    suspend fun prefetch(api: MusicRepository, session: Session, item: Item) {}

    suspend fun download(
        api: MusicRepository,
        session: Session,
        item: Item,
        tracks: List<Item> = emptyList(),
    ) {}

    suspend fun removeDownload(session: Session, item: Item) {}

    fun downloadedCollections(session: Session): Set<String> = emptySet()

    suspend fun refresh(api: MusicRepository, session: Session, force: Boolean = false) {}

    suspend fun lyrics(session: Session, id: String): LyricDocument? = null

    suspend fun item(session: Session, id: String): Item? = null

    suspend fun tracks(session: Session, id: String): List<Item>? = null

    fun image(session: Session, item: Item): String? = null

    fun downloaded(session: Session): Set<String> = emptySet()

    suspend fun loadPlayback(session: Session): SavedPlayback? = null

    suspend fun savePlayback(session: Session, playback: SavedPlayback) {}

    suspend fun offlineLibrary(session: Session): SavedLibrary? = null

    suspend fun loadLibrary(session: Session): SavedLibrary? = null

    suspend fun saveLibrary(session: Session, library: SavedLibrary) {}

    fun close() {}
}

object NoLocalMediaLibrary : LocalMediaLibrary {
    override val revision = MutableStateFlow(0)

    override suspend fun source(api: MusicRepository, session: Session, item: Item) =
        PlaybackSource(
            api.stream(session, item.id),
            session.server + "/" + session.user.id + "/" + item.id,
        )
}

private val NoDownloadProgress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
