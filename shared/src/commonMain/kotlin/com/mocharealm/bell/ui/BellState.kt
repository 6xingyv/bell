// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.domain.model.MusicMix
import com.mocharealm.bell.domain.model.PlaybackHistoryEntry
import com.mocharealm.bell.domain.model.PlaylistInfo
import com.mocharealm.bell.domain.model.SearchScope
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.UserPolicy
import com.mocharealm.bell.domain.repository.SavedLibrary

data class BellState(
    val listening: com.mocharealm.bell.domain.model.ListeningState =
        com.mocharealm.bell.domain.model.ListeningState(),
    val session: Session? = null,
    val loading: Boolean = false,
    val refreshingHome: Boolean = false,
    val error: MusicError? = null,
    val albums: List<Item> = emptyList(),
    val songs: List<Item> = emptyList(),
    val results: List<Item> = emptyList(),
    val playlists: List<Item> = emptyList(),
    val latest: List<Item> = emptyList(),
    val recentlyPlayed: List<Item> = emptyList(),
    val recommendations: List<Item> = emptyList(),
    val genres: List<Item> = emptyList(),
    val mixes: List<MusicMix> = emptyList(),
    val pins: List<Item> = emptyList(),
    val searchHistory: List<Item> = emptyList(),
    val searchScope: SearchScope = SearchScope.Library,
    val creditsItem: Item? = null,
    val credits: com.mocharealm.bell.domain.model.CreditDocument? = null,
    val creditsLoading: Boolean = false,
    val dialog: LibraryDialog? = null,
    val policy: UserPolicy = UserPolicy(),
    val playlistInfo: PlaylistInfo? = null,
    val artists: List<Item> = emptyList(),
    val artistAlbums: List<Item> = emptyList(),
    val artistDescending: Boolean = false,
    val userImageTag: String? = null,
    val offlineLibrary: SavedLibrary? = null,
    val albumOffline: Boolean = false,
    val album: Item? = null,
    val albumSongs: List<Item> = emptyList(),
    val queueKeys: List<Long> = emptyList(),
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val manualQueueCount: Int = 0,
    val queueContext: String? = null,
    val autoPlay: Boolean = false,
    val autoMix: Boolean = false,
    val queue: List<Item> = emptyList(),
    val queueIndex: Int = -1,
    val audio: AudioSnapshot = AudioSnapshot(),
    val buffering: Boolean = false,
    val downloadedCollections: Set<String> = emptySet(),
    val downloaded: Set<String> = emptySet(),
    val downloading: Set<String> = emptySet(),
    val mediaRevision: Int = 0,
    val lyrics: LyricDocument = LyricDocument(),
    val repeat: Boolean = false,
    val repeatOne: Boolean = false,
    val shuffle: Boolean = false,
) {
    val current
        get() = queue.getOrNull(queueIndex)
}
