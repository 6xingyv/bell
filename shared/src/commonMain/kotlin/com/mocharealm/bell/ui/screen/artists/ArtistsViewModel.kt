// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.artists

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class ArtistsState(val artists: List<Item>, val artistDescending: Boolean)

sealed interface ArtistsIntent {
    data class OpenAlbum(val item: Item, val offlineOnly: Boolean = false) : ArtistsIntent
}

class ArtistsViewModel(private val store: BellStore) :
    ScreenViewModel<ArtistsState, ArtistsIntent>(
        store,
        { s -> ArtistsState(s.artists, s.artistDescending) },
    ) {
    override fun accept(intent: ArtistsIntent) {
        store.accept(
            when (intent) {
                is ArtistsIntent.OpenAlbum -> BellIntent.OpenAlbum(intent.item, intent.offlineOnly)
            }
        )
    }
}
