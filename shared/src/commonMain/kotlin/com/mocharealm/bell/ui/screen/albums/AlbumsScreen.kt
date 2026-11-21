// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.albums

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AlbumsScreen(
    model: BellStore,
    state: BellState,
    albums: List<Item>,
    back: () -> Unit,
) {
    val text_albums = stringResource(Res.string.albums)
    val text_refresh_library = stringResource(Res.string.refresh_library)
    val viewModel = rememberScreenViewModel(model) { AlbumsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    DetailList(
        text_albums,
        back,
        actions = {
            MusicMenu(
                listOf(
                    MusicMenuAction(text_refresh_library, "repeat") {
                        viewModel.accept(AlbumsIntent.Refresh())
                    }
                )
            )
        },
    ) {
        items(albums.chunked(2)) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { AlbumTile(model, state, it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
