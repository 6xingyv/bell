// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.downloaded

import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.mvi.ScreenViewModel
import com.mocharealm.bell.ui.utils.downloadedState

sealed interface DownloadedIntent {
    data object Reload : DownloadedIntent
}

class DownloadedViewModel(private val store: BellStore) :
    ScreenViewModel<BellState, DownloadedIntent>(store, ::downloadedState) {
    override fun accept(intent: DownloadedIntent) {
        when (intent) {
            DownloadedIntent.Reload -> store.accept(BellIntent.RefreshOffline)
        }
    }
}
