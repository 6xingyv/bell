// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.snapping.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.nav.musicCorners
import com.mocharealm.bell.ui.nav.musicSharedContainer
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.screen.home.composables.MusicShelf
import com.mocharealm.bell.ui.utils.formatBellString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeScreen(
    model: BellStore,
    state: BellState,
    albums: List<Item>,
    title: String,
    navigate: (String) -> Unit,
) {
    val text_made_for_you = stringResource(Res.string.made_for_you)
    val text_home_empty_description = stringResource(Res.string.home_empty_description)
    val text_no_music = stringResource(Res.string.no_music)
    val text_music_recommendations = stringResource(Res.string.music_recommendations)
    val text_recently_added = stringResource(Res.string.recently_added)
    val text_recently_played = stringResource(Res.string.recently_played)
    val text_recommended_for_you = stringResource(Res.string.recommended_for_you)

    val viewModel = rememberScreenViewModel(model) { HomeViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    val recommendationList = rememberLazyListState()
    val mixList = rememberLazyListState()
    // The row owns its gutters so scrolling cards reach the window edge, not a padded clip.
    MusicPage(
        title,
        horizontalInset = 0.dp,
        refreshing = state.refreshingHome,
        onRefresh = { model.accept(BellIntent.PullToRefreshHome) },
    ) {
        if (screenState.recommendations.isNotEmpty())
            item {
                Spacer(Modifier.height(24.dp))
                MusicText(
                    text_music_recommendations,
                    24,
                    weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    Modifier.fillMaxWidth(),
                    state = recommendationList,
                    flingBehavior =
                        rememberSnapFlingBehavior(
                            recommendationList,
                            snapPosition = SnapPosition.Start,
                        ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(screenState.recommendations, key = { it.id }) { item ->
                        val link =
                            rememberArtworkLink(item.id) {
                                viewModel.accept(HomeIntent.OpenItem(item))
                            }
                        Box(
                            Modifier.width(240.dp)
                                .height(320.dp)
                                .clickable(onClick = link.open)
                                .then(musicSharedContainer(link.key, musicCorners(16.dp)))
                                .shadow(8.dp, musicShape(16.dp))
                                .clip(musicShape(16.dp))
                        ) {
                            MusicArtwork(
                                model,
                                state,
                                item,
                                Modifier.fillMaxSize(),
                                16.dp,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(musicShape(16.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = .9f),
                                            ),
                                            startY = 250f,
                                        )
                                    )
                            )
                            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                MusicText(
                                    text_recommended_for_you,
                                    13,
                                    Color.White.copy(alpha = .65f),
                                )
                                Spacer(Modifier.height(4.dp))
                                MusicText(
                                    item.name,
                                    18,
                                    Color.White,
                                    FontWeight.SemiBold,
                                    lines = 2,
                                )
                                Spacer(Modifier.height(4.dp))
                                MusicText(item.artist, 14, Color.White.copy(alpha = .8f))
                            }
                        }
                    }
                }
            }
        if (screenState.recentlyPlayed.isNotEmpty())
            item { MusicShelf(text_recently_played, model, state, screenState.recentlyPlayed) }
        if (screenState.mixes.isNotEmpty())
            item {
                Spacer(Modifier.height(16.dp))
                MusicText(
                    text_made_for_you,
                    24,
                    weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    Modifier.fillMaxWidth(),
                    state = mixList,
                    flingBehavior =
                        rememberSnapFlingBehavior(mixList, snapPosition = SnapPosition.Start),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(screenState.mixes, key = { it.seed.id }) { mix ->
                        val link =
                            rememberArtworkLink("jellify-mix:${mix.seed.id}") {
                                viewModel.accept(HomeIntent.OpenMix(mix))
                            }
                        Column(Modifier.width(240.dp).clickable(onClick = link.open)) {
                            Box(
                                Modifier.height(240.dp)
                                    .fillMaxWidth()
                                    .then(musicSharedContainer(link.key, musicCorners(12.dp)))
                                    .clip(musicShape(12.dp))
                            ) {
                                MusicArtwork(
                                    model,
                                    state,
                                    mix.seed,
                                    Modifier.fillMaxSize(),
                                    12.dp,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = .7f),
                                                )
                                            )
                                        )
                                )
                                MusicText(
                                    text_made_for_you,
                                    32,
                                    Color.White,
                                    FontWeight.Bold,
                                    Modifier.align(Alignment.BottomStart).padding(16.dp),
                                    lines = 2,
                                )
                            }
                            Spacer(Modifier.height(7.dp))
                            MusicText(
                                formatBellString(
                                    stringResource(Res.string.mix_title),
                                    mix.seed.name,
                                ),
                                16,
                                weight = FontWeight.SemiBold,
                            )
                            MusicText(stringResource(Res.string.mix_description), 13, Color.Gray)
                        }
                    }
                }
            }
        if (screenState.latest.isNotEmpty())
            item { MusicShelf(text_recently_added, model, state, screenState.latest) }
        screenState.genres
            .map { genre ->
                genre to
                    screenState.albums.filter { album ->
                        album.type == "MusicAlbum" &&
                            album.genres.any { it.equals(genre.name, true) }
                    }
            }
            .filter { it.second.isNotEmpty() }
            .take(4)
            .forEach { (genre, matching) ->
                if (matching.isNotEmpty())
                    item(key = "genre:${genre.id}") {
                        MusicShelf(genre.name, model, state, matching.take(12))
                    }
            }
        if (
            screenState.recommendations.isEmpty() &&
                screenState.latest.isEmpty() &&
                !screenState.loading
        )
            item { EmptyMusic(text_no_music, text_home_empty_description) }
    }
}
