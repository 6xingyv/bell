// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.settings

import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class SettingsState(val session: Session?, val userImageTag: String?)

sealed interface SettingsIntent {
    data class Refresh(val forceMetadata: Boolean = true) : SettingsIntent

    data object Logout : SettingsIntent
}

class SettingsViewModel(private val store: BellStore) :
    ScreenViewModel<SettingsState, SettingsIntent>(
        store,
        { s -> SettingsState(s.session, s.userImageTag) },
    ) {
    override fun accept(intent: SettingsIntent) {
        store.accept(
            when (intent) {
                is SettingsIntent.Refresh -> BellIntent.Refresh(intent.forceMetadata)
                SettingsIntent.Logout -> BellIntent.Logout
            }
        )
    }
}
