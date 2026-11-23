// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.MusicMix
import com.mocharealm.bell.domain.model.SearchScope

/** All mutations enter the store through this immutable command contract. */
sealed interface BellIntent {
    data class HomeVisibility(val visible: Boolean) : BellIntent

    data object RefreshHome : BellIntent

    data object PullToRefreshHome : BellIntent

    data object LoadListeningGroups : BellIntent

    data class CreateListeningGroup(val name: String) : BellIntent

    data class JoinListeningGroup(val id: String) : BellIntent

    data object LeaveListeningGroup : BellIntent

    data class MoveQueue(val fromKey: Long, val toKey: Long) : BellIntent

    data class PlayHistory(val key: Long) : BellIntent

    data class PlayNext(val item: Item) : BellIntent

    data class CancelDownload(val item: Item) : BellIntent

    data class EnqueueMany(val items: List<Item>) : BellIntent

    data object RefreshOffline : BellIntent

    data object ClearHistory : BellIntent

    data object ClearQueue : BellIntent

    data object AutoPlay : BellIntent

    data object AutoMix : BellIntent

    data object ClearError : BellIntent

    data class Login(val server: String, val user: String, val password: String) : BellIntent

    data class Refresh(val forceMetadata: Boolean = true) : BellIntent

    data class RemoveDownload(val item: Item) : BellIntent

    data class Download(val item: Item, val tracks: List<Item> = emptyList()) : BellIntent

    data object Logout : BellIntent

    data class OpenAlbum(val item: Item, val offlineOnly: Boolean = false) : BellIntent

    data object CloseAlbum : BellIntent

    data class Search(val query: String) : BellIntent

    data class SearchScope(val value: com.mocharealm.bell.domain.model.SearchScope) : BellIntent

    data class ArtistSort(val descending: Boolean) : BellIntent

    data class Station(val item: Item) : BellIntent

    data object CloseCredits : BellIntent

    data class Credits(val item: Item) : BellIntent

    data class GoToArtist(val item: Item, val offlineOnly: Boolean = false) : BellIntent

    data class OpenItem(
        val item: Item,
        val rememberSearch: Boolean = false,
        val offlineOnly: Boolean = false,
    ) : BellIntent

    data class OpenMix(val mix: MusicMix) : BellIntent

    data class Pin(val item: Item) : BellIntent

    data class EditPins(val order: List<String>, val removed: Set<String>) : BellIntent

    data class SetLibrarySections(val sections: List<String>) : BellIntent

    data class RememberSearch(val item: Item) : BellIntent

    data object ClearSearchHistory : BellIntent

    data class SuggestLess(val item: Item) : BellIntent

    data class Dialog(val value: LibraryDialog?) : BellIntent

    data class AddToPlaylist(val playlist: Item, val items: List<Item>) : BellIntent

    data class CreatePlaylist(val name: String, val items: List<Item>) : BellIntent

    data class RenamePlaylist(val item: Item, val name: String) : BellIntent

    data class Delete(val item: Item) : BellIntent

    data class Play(val items: List<Item>, val index: Int = 0, val positionMs: Long = 0) :
        BellIntent

    data class SetPaused(val paused: Boolean) : BellIntent

    data object Toggle : BellIntent

    data class Seek(val ms: Long) : BellIntent

    data class Next(val automatic: Boolean = false) : BellIntent

    data object Previous : BellIntent

    data object Shuffle : BellIntent

    data object Repeat : BellIntent

    data class Enqueue(val item: Item) : BellIntent

    data class Remove(val index: Int) : BellIntent

    data class Favorite(val item: Item) : BellIntent
}
