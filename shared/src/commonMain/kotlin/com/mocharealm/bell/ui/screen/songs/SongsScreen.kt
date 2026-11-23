// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.songs

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
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.MusicTrackRow
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.Routes
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SongsScreen(
    model: BellStore,
    state: BellState,
    songs: List<Item>,
    title: String,
    back: (() -> Unit)?,
) {
    val text_favorites_empty_description = stringResource(Res.string.favorites_empty_description)
    val text_no_songs = stringResource(Res.string.no_songs)
    val text_songs_empty_description = stringResource(Res.string.songs_empty_description)
    val text_play_all = stringResource(Res.string.play_all)
    val text_shuffle = stringResource(Res.string.shuffle)

    val viewModel = rememberScreenViewModel(model) { SongsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    DetailList(
        title,
        back,
        actions = {
            MusicMenu(
                listOf(
                    MusicMenuAction(text_play_all, "play") {
                        viewModel.accept(SongsIntent.Play(songs))
                    },
                    MusicMenuAction(text_shuffle, "shuffle") {
                        viewModel.accept(SongsIntent.Play(songs.shuffled()))
                    },
                )
            )
        },
    ) {
        itemsIndexed(songs, key = { _, it -> it.id }) { index, item ->
            MusicTrackRow(model, state, item, index, songs)
        }
        if (songs.isEmpty())
            item {
                EmptyMusic(
                    text_no_songs,
                    if (title == Routes.Favorites) text_favorites_empty_description
                    else text_songs_empty_description,
                )
            }
    }
}
