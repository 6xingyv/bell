// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.songs

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class SongsState(val songs: List<Item>)

sealed interface SongsIntent {
    data class Play(val items: List<Item>, val index: Int = 0, val positionMs: Long = 0) :
        SongsIntent
}

class SongsViewModel(private val store: BellStore) :
    ScreenViewModel<SongsState, SongsIntent>(store, { s -> SongsState(s.songs) }) {
    override fun accept(intent: SongsIntent) {
        store.accept(
            when (intent) {
                is SongsIntent.Play ->
                    BellIntent.Play(intent.items, intent.index, intent.positionMs)
            }
        )
    }
}
