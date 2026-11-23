// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.albums

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class AlbumsState(val albums: List<Item>)

sealed interface AlbumsIntent {
    data class Refresh(val forceMetadata: Boolean = true) : AlbumsIntent
}

class AlbumsViewModel(private val store: BellStore) :
    ScreenViewModel<AlbumsState, AlbumsIntent>(store, { s -> AlbumsState(s.albums) }) {
    override fun accept(intent: AlbumsIntent) {
        store.accept(
            when (intent) {
                is AlbumsIntent.Refresh -> BellIntent.Refresh(intent.forceMetadata)
            }
        )
    }
}
