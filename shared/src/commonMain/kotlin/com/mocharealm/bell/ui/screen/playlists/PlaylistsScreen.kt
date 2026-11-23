// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.playlists

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
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.CoverListRow
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.Routes
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlaylistsScreen(
    model: BellStore,
    state: BellState,
    back: () -> Unit,
    navigate: (String) -> Unit,
) {
    val offline = LocalMusicOffline.current

    val text_favorite_songs = stringResource(Res.string.favorite_songs)
    val text_playlists = stringResource(Res.string.playlists)
    val text_refresh_library = stringResource(Res.string.refresh_library)

    val viewModel = rememberScreenViewModel(model) { PlaylistsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    DetailList(
        text_playlists,
        back,
        actions = {
            MusicMenu(
                listOf(
                    MusicMenuAction(text_refresh_library, "repeat") {
                        model.accept(BellIntent.Refresh())
                    }
                )
            )
        },
    ) {
        item {
            CoverListRow(
                text_favorite_songs,
                null,
                {
                    Box(
                        Modifier.size(64.dp).clip(musicShape(5.dp)).background(Color(0xFFEEEEEF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicIcon("star.fill", MusicAccent, 36.dp)
                    }
                },
            ) {
                navigate(Routes.FavoriteSongs)
            }
        }
        items(screenState.playlists, key = { it.id }) { item ->
            val link =
                rememberArtworkLink(item.id) {
                    viewModel.accept(PlaylistsIntent.OpenAlbum(item, offline))
                }
            CoverListRow(
                item.name,
                null,
                {
                    MusicArtwork(
                        model,
                        state,
                        item,
                        Modifier.size(64.dp),
                        5.dp,
                        sharedKey = link.key,
                    )
                },
                link.open,
            )
        }
    }
}
