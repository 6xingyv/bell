// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.home

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.MusicMix
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class HomeState(
    val recommendations: List<Item>,
    val recentlyPlayed: List<Item>,
    val mixes: List<MusicMix>,
    val latest: List<Item>,
    val genres: List<Item>,
    val albums: List<Item>,
    val loading: Boolean,
)

sealed interface HomeIntent {
    data class OpenItem(val item: Item, val rememberSearch: Boolean = false) : HomeIntent

    data class OpenMix(val mix: MusicMix) : HomeIntent
}

class HomeViewModel(private val store: BellStore) :
    ScreenViewModel<HomeState, HomeIntent>(
        store,
        { s ->
            HomeState(
                s.recommendations,
                s.recentlyPlayed,
                s.mixes,
                s.latest,
                s.genres,
                s.albums,
                s.loading,
            )
        },
    ) {
    override fun accept(intent: HomeIntent) {
        store.accept(
            when (intent) {
                is HomeIntent.OpenItem -> BellIntent.OpenItem(intent.item, intent.rememberSearch)
                is HomeIntent.OpenMix -> BellIntent.OpenMix(intent.mix)
            }
        )
    }
}
