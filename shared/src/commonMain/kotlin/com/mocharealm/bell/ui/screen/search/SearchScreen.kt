// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.search

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.BellField
import com.mocharealm.bell.ui.composables.BellGroup
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.LocalMusicNativeSearch
import com.mocharealm.bell.ui.composables.LocalMusicSearchFocusChange
import com.mocharealm.bell.ui.composables.LocalMusicSearchFocused
import com.mocharealm.bell.ui.composables.LocalMusicSearchQuery
import com.mocharealm.bell.ui.composables.LocalMusicTabletMode
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicSearchFieldGroup
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.screen.search.composables.MusicSearchRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SearchScreen(model: BellStore, state: BellState) {
    val text_clear = stringResource(Res.string.clear)
    val text_genres_empty_description = stringResource(Res.string.genres_empty_description)
    val text_browse_music = stringResource(Res.string.browse_music)
    val text_search_history_empty = stringResource(Res.string.search_history_empty)
    val text_recent_searches = stringResource(Res.string.recent_searches)
    val text_search = stringResource(Res.string.search)
    val text_search_placeholder = stringResource(Res.string.search_placeholder)
    val gridColumns = LocalMusicGridColumns.current
    val viewModel = rememberScreenViewModel(model) { SearchViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    var localQuery by remember { mutableStateOf("") }
    val tabletMode = LocalMusicTabletMode.current
    val query = if (tabletMode) LocalMusicSearchQuery.current else localQuery
    val nativeSearch = LocalMusicNativeSearch.current
    val onSearchFocusChanged = LocalMusicSearchFocusChange.current
    LaunchedEffect(model) { model.accept(com.mocharealm.bell.ui.BellIntent.RefreshHome) }
    val focused = LocalMusicSearchFocused.current
    MusicPage(text_search, largeTitle = !focused && !tabletMode) {
        if (!nativeSearch && !tabletMode)
            item(key = "search-field") {
                MusicSearchFieldGroup {
                    BellGroup {
                        BellField(
                            query,
                            {
                                localQuery = it
                                viewModel.accept(SearchIntent.Search(it))
                            },
                            text_search_placeholder,
                            onFocusChanged = onSearchFocusChanged,
                            onDone = { viewModel.accept(SearchIntent.Search(query)) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        if (focused && query.isBlank()) {
            item {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicText(
                        text_recent_searches,
                        20,
                        weight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    MusicText(
                        text_clear,
                        16,
                        MusicAccent,
                        modifier =
                            Modifier.clickable {
                                viewModel.accept(SearchIntent.ClearSearchHistory)
                            },
                    )
                }
            }
            items(screenState.searchHistory, key = { it.id }) { item ->
                MusicSearchRow(model, state, item, true)
            }
            if (screenState.searchHistory.isEmpty())
                item { EmptyMusic(text_recent_searches, text_search_history_empty) }
        } else if (query.isBlank()) {
            items(screenState.genres.chunked(gridColumns)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { genre ->
                        val colors =
                            listOf(
                                Color(0xFFD72A6B),
                                Color(0xFF4979D8),
                                Color(0xFFA78A0B),
                                Color(0xFF7041B5),
                                Color(0xFFE45638),
                            )
                        val color = colors[(genre.name.hashCode() and Int.MAX_VALUE) % colors.size]
                        val art =
                            state.albums.firstOrNull {
                                it.genres.any { tag -> tag.equals(genre.name, true) }
                            }
                                ?: state.songs.firstOrNull {
                                    it.genres.any { tag -> tag.equals(genre.name, true) }
                                }
                                ?: genre
                        Box(
                            Modifier.weight(1f)
                                .aspectRatio(1.78f)
                                .clip(musicShape(14.dp))
                                .background(color)
                                .clickable { viewModel.accept(SearchIntent.OpenItem(genre)) }
                        ) {
                            MusicArtwork(
                                model,
                                state,
                                art,
                                Modifier.fillMaxSize(),
                                14.dp,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            Box(
                                Modifier.fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                color.copy(alpha = .25f),
                                                color.copy(alpha = .85f),
                                            )
                                        )
                                    )
                            )
                            MusicText(
                                genre.name,
                                18,
                                Color.White,
                                FontWeight.Bold,
                                Modifier.align(Alignment.BottomStart).padding(12.dp),
                                lines = 2,
                            )
                        }
                    }
                    repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (screenState.genres.isEmpty())
                item { EmptyMusic(text_browse_music, text_genres_empty_description) }
        }
        if (!nativeSearch && query.isNotBlank())
            items(screenState.results, key = { it.id }) { MusicSearchRow(model, state, it, true) }
    }
}
