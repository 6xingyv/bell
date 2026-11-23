// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.credits

import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class CreditsState(
    val item: Item?,
    val document: CreditDocument?,
    val loading: Boolean,
    val currentId: String?,
    val paused: Boolean,
)

sealed interface CreditsIntent {
    data object Play : CreditsIntent

    data object Lyrics : CreditsIntent
}

class CreditsViewModel(private val store: BellStore) :
    ScreenViewModel<CreditsState, CreditsIntent>(
        store,
        { s ->
            CreditsState(s.creditsItem, s.credits, s.creditsLoading, s.current?.id, s.audio.paused)
        },
    ) {
    override fun accept(intent: CreditsIntent) {
        val s = store.state.value
        val item = s.creditsItem ?: return
        when (intent) {
            CreditsIntent.Play ->
                store.accept(
                    if (s.current?.id == item.id) BellIntent.Toggle
                    else BellIntent.Play(listOf(item))
                )
            CreditsIntent.Lyrics ->
                if (s.current?.id != item.id) store.accept(BellIntent.Play(listOf(item)))
        }
    }
}
