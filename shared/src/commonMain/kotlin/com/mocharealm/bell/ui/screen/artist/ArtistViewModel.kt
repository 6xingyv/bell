// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.artist

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class ArtistState(val artist: Item?, val albums: List<Item>, val songs: List<Item>)

sealed interface ArtistIntent {
    data class Play(val items: List<Item>, val index: Int = 0) : ArtistIntent
}

class ArtistViewModel(private val store: BellStore) :
    ScreenViewModel<ArtistState, ArtistIntent>(
        store,
        { ArtistState(it.album, it.artistAlbums, it.albumSongs) },
    ) {
    override fun accept(intent: ArtistIntent) {
        when (intent) {
            is ArtistIntent.Play -> store.accept(BellIntent.Play(intent.items, intent.index))
        }
    }
}
