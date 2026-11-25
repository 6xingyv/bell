// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.nav.*
import com.mocharealm.bell.ui.screen.login.*
import com.mocharealm.bell.ui.screen.player.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    @Test
    fun searchDoesNotReplaceTheSelectedRootTab() {
        val navigation = NavigationViewModel()
        navigation.accept(NavigationIntent.SelectTab(Routes.Home))
        navigation.accept(NavigationIntent.Navigate(Routes.Search))
        assertEquals(Routes.Home, navigation.state.value.tab)
        assertEquals(Routes.Search, navigation.state.value.route)
        navigation.accept(NavigationIntent.Navigate(Routes.Home))
        assertEquals(NavigationState(Routes.Home, Routes.Home), navigation.state.value)
    }

    @Test
    fun switchingPlayerPanelsRevealsControlsAndSelectingAgainClosesThePanel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = HttpClient(MockEngine { error("No network request expected") })
        val store = store(client, backgroundScope)
        val viewModel = PlayerViewModel(store)
        try {
            viewModel.accept(PlayerIntent.SelectPanel(PlayerPanel.Lyrics))
            viewModel.accept(PlayerIntent.HideControls)
            assertEquals(
                PlayerPresentation(PlayerPanel.Lyrics, false),
                viewModel.presentation.value,
            )
            viewModel.accept(PlayerIntent.SelectPanel(PlayerPanel.Queue))
            assertEquals(PlayerPresentation(PlayerPanel.Queue, true), viewModel.presentation.value)
            viewModel.accept(PlayerIntent.SelectPanel(PlayerPanel.Queue))
            assertEquals(PlayerPresentation(PlayerPanel.Cover, true), viewModel.presentation.value)
        } finally {
            viewModel.close()
            store.close()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loginSubmitClearsThePasswordButRetainsTheServerAndUser() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = HttpClient(MockEngine { awaitCancellation() })
        val store = store(client, backgroundScope)
        val viewModel = LoginViewModel(store)
        try {
            viewModel.accept(LoginIntent.ServerChanged("http://localhost"))
            viewModel.accept(LoginIntent.UserChanged("listener"))
            viewModel.accept(LoginIntent.PasswordChanged("temporary-password"))
            viewModel.accept(LoginIntent.Submit)
            assertEquals(LoginForm("http://localhost", "listener", ""), viewModel.form.value)
        } finally {
            viewModel.close()
            store.close()
            client.close()
            Dispatchers.resetMain()
        }
    }

    private fun store(client: HttpClient, scope: CoroutineScope) =
        BellStore(
            JellyfinApi(client),
            object : SessionStore {
                override val deviceId = "test"

                override fun load(): Session? = null

                override fun save(session: Session?) {}
            },
            object : AudioPlayer {
                override suspend fun play(url: String, durationMs: Long) {}

                override fun pause(paused: Boolean) {}

                override fun seek(positionMs: Long) {}

                override fun snapshot() = AudioSnapshot()

                override fun stop() {}

                override fun close() {}
            },
            scope,
        )
}
