// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

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
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.nav.Routes
import com.mocharealm.bell.ui.utils.displayText
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryScreen(
    model: BellStore,
    state: BellState,
    albums: List<Item>,
    navigate: (String) -> Unit,
    title: String? = null,
    showDownloadedEntry: Boolean = true,
    preserveLatestGridCellSize: Boolean = false,
) {
    val text_albums = stringResource(Res.string.albums)
    val text_artists = stringResource(Res.string.artists)
    val text_library = stringResource(Res.string.library)
    val text_library_empty_title = stringResource(Res.string.library_empty_title)
    val text_library_empty_description = stringResource(Res.string.library_empty_description)
    val text_playlists = stringResource(Res.string.playlists)
    val text_recently_added = stringResource(Res.string.recently_added)
    val emptyDownloaded = stringResource(Res.string.downloads_empty_title)
    val emptyDownloadedDescription = stringResource(Res.string.downloads_empty_description)
    val downloaded = stringResource(Res.string.downloaded)
    val text_songs = stringResource(Res.string.songs)
    val gridColumns = LocalMusicGridColumns.current
    val pinGridColumns = maxOf(gridColumns, 3)

    val viewModel = rememberScreenViewModel(model) { LibraryViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)
    val errorText = screenState.error?.displayText()

    MusicPage(title ?: text_library) {
        if (screenState.pins.isNotEmpty()) {
            items(screenState.pins.chunked(pinGridColumns)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { AlbumTile(model, state, it, Modifier.weight(1f), compact = true) }
                    repeat(pinGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        item {
            Column {
                (listOf(
                        Triple(text_playlists, "playlist", Routes.Playlists),
                        Triple(text_artists, "microphone", Routes.Artists),
                        Triple(text_albums, "albums", Routes.Albums),
                        Triple(text_songs, "songs", Routes.Songs),
                    ) +
                        if (showDownloadedEntry)
                            listOf(Triple(downloaded, "downloadOutline", Routes.Downloaded))
                        else emptyList())
                    .forEach { (title, icon, destination) ->
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable {
                                navigate(destination)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicIcon(icon, MusicAccent, 25.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Row(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MusicText(title, 17, modifier = Modifier.weight(1f))
                                    MusicIcon("chevron", Color(0xFFC5C5C7), 19.dp)
                                }
                                // IMG_3426: x=61pt..377pt. The separator includes the
                                // chevron and extends 4pt past the 20pt card/content gutter.
                                Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                                    drawRect(
                                        Color(0xFFE4E4E6),
                                        size =
                                            androidx.compose.ui.geometry.Size(
                                                size.width + 4.dp.toPx(),
                                                size.height,
                                            ),
                                    )
                                }
                            }
                        }
                    }
            }
        }
        item {
            Spacer(Modifier.height(28.dp))
            MusicText(text_recently_added, 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
        }
        if (screenState.latest.isEmpty())
            item {
                EmptyMusic(
                    if (showDownloadedEntry) text_library_empty_title else emptyDownloaded,
                    if (showDownloadedEntry) text_library_empty_description
                    else emptyDownloadedDescription,
                )
            }
        items(screenState.latest.chunked(gridColumns)) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { AlbumTile(model, state, it, Modifier.weight(1f)) }
                if (preserveLatestGridCellSize)
                    repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                else if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        errorText?.let { item { MusicText(it, 14, MusicAccent, lines = 3) } }
    }
}
