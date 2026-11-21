// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.downloaded

import androidx.compose.runtime.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.Routes
import com.mocharealm.bell.ui.screen.library.LibraryScreen
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadedScreen(model: BellStore, state: BellState, navigate: (String) -> Unit) {
    val viewModel = rememberScreenViewModel(model) { DownloadedViewModel(model) }
    val observed by viewModel.state.collectAsState()
    val catalog = if (state === model.state.value) observed else viewModel.snapshot(state)
    CompositionLocalProvider(LocalMusicOffline provides true) {
        LibraryScreen(
            model,
            catalog,
            catalog.albums,
            { navigate(Routes.downloaded(it)) },
            title = stringResource(Res.string.downloaded),
            showDownloadedEntry = false,
            preserveLatestGridCellSize = true,
        )
    }
}
