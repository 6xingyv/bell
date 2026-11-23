// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class LibraryState(val pins: List<Item>, val latest: List<Item>, val error: MusicError?)

sealed interface LibraryIntent {
    data object Refresh : LibraryIntent
}

class LibraryViewModel(private val store: BellStore) :
    ScreenViewModel<LibraryState, LibraryIntent>(
        store,
        { s -> LibraryState(s.pins, s.latest, s.error) },
    ) {
    override fun accept(intent: LibraryIntent) {
        store.accept(
            when (intent) {
                LibraryIntent.Refresh -> BellIntent.Refresh()
            }
        )
    }
}
