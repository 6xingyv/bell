// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.artists

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicAlphabetIndex
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistsScreen(model: BellStore, state: BellState) {
    val offline = LocalMusicOffline.current

    val text_artists = stringResource(Res.string.artists)
    val text_artists_empty_description = stringResource(Res.string.artists_empty_description)
    val text_no_artists = stringResource(Res.string.no_artists)

    val viewModel = rememberScreenViewModel(model) { ArtistsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    val artists =
        remember(screenState.artists, screenState.artistDescending) {
            screenState.artists
                .sortedBy { it.sortName ?: it.name.lowercase() }
                .let { if (screenState.artistDescending) it.reversed() else it }
        }
    val groups =
        remember(artists) {
            artists
                .groupBy { artist ->
                    (artist.sortName ?: artist.name.removePrefix("The "))
                        .trim()
                        .firstOrNull()
                        ?.takeIf { it.isLetter() }
                        ?.uppercase() ?: "#"
                }
                .toList()
        }
    val sections =
        remember(groups) {
            var index = 1
            groups.map { (letter, items) -> (letter to index).also { index += items.size + 1 } }
        }
    Box(Modifier.fillMaxSize()) {
        MusicPage(text_artists) {
            groups.forEach { (letter, artists) ->
                stickyHeader(key = "section:$letter") {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(if (LocalMusicDark.current) Color.Black else Color.White)
                            .padding(top = 24.dp, bottom = 8.dp)
                    ) {
                        MusicText(letter, 18, weight = FontWeight.SemiBold)
                    }
                }
                items(artists, key = { it.id }) { artist ->
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).clickable {
                            viewModel.accept(ArtistsIntent.OpenAlbum(artist, offline))
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MusicArtwork(
                            model,
                            state,
                            artist,
                            Modifier.size(48.dp).clip(CircleShape),
                            24.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MusicText(artist.name, 17, modifier = Modifier.weight(1f))
                                MusicIcon("chevron", Color(0xFFC5C5C7), 19.dp)
                            }
                            Box(Modifier.fillMaxWidth().height(.5.dp).background(Color(0xFFE4E4E6)))
                        }
                    }
                }
            }
            if (artists.isEmpty() && !state.loading)
                item { EmptyMusic(text_no_artists, text_artists_empty_description) }
        }
        MusicAlphabetIndex(sections)
    }
}
