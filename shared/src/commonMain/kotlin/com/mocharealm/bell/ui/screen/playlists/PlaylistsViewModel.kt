// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.playlists

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class PlaylistsState(val playlists: List<Item>)

sealed interface PlaylistsIntent {
    data class OpenAlbum(val item: Item, val offlineOnly: Boolean = false) : PlaylistsIntent
}

class PlaylistsViewModel(private val store: BellStore) :
    ScreenViewModel<PlaylistsState, PlaylistsIntent>(store, { s -> PlaylistsState(s.playlists) }) {
    override fun accept(intent: PlaylistsIntent) {
        store.accept(
            when (intent) {
                is PlaylistsIntent.OpenAlbum ->
                    BellIntent.OpenAlbum(intent.item, intent.offlineOnly)
            }
        )
    }
}
