// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumArtistLink
import com.mocharealm.bell.ui.composables.AlbumDownloadButton
import com.mocharealm.bell.ui.composables.LocalMusicTabletMode
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.MusicTrackRow
import com.mocharealm.bell.ui.composables.fittedMusicArtwork
import com.mocharealm.bell.ui.composables.musicSwipeDivider
import com.mocharealm.bell.ui.composables.rememberMusicArtworkColor
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.LocalArtworkNavigation
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.screen.album.composables.MusicAlbumOverview
import com.mocharealm.bell.ui.screen.album.composables.albumArtistDisplay
import com.mocharealm.bell.ui.screen.album.composables.albumTrackArtist
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.formatBellString
import com.mocharealm.bell.ui.utils.musicBackground
import org.jetbrains.compose.resources.stringResource

@Composable
fun AlbumScreen(model: BellStore, state: BellState, back: () -> Unit) {
    val text_album_track_summary = stringResource(Res.string.album_track_summary)
    val dateFormat = stringResource(Res.string.release_date_format)
    val text_disc_number = stringResource(Res.string.disc_number)
    val text_play = stringResource(Res.string.play)
    val text_play_album = stringResource(Res.string.play_album)

    val viewModel = rememberScreenViewModel(model) { AlbumViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    val album = screenState.album ?: return
    val tabletMode = LocalMusicTabletMode.current
    val albumTitle =
        if (album.type == "Mix") stringResource(Res.string.mix_title, album.name) else album.name
    val songs =
        remember(screenState.albumSongs, album.type) {
            if (album.type == "MusicAlbum")
                screenState.albumSongs.sortedWith(
                    compareBy<Item> { (it.discNumber ?: 1).coerceAtLeast(1) }
                        .thenBy { it.number ?: Int.MAX_VALUE }
                )
            else screenState.albumSongs
        }
    val discs =
        remember(songs, album.type) {
            if (album.type == "MusicAlbum")
                songs
                    .groupBy { (it.discNumber ?: 1).coerceAtLeast(1) }
                    .toList()
                    .sortedBy { it.first }
            else listOf(1 to songs)
        }
    val multiDisc = discs.size > 1
    val albumAccent by rememberMusicArtworkColor(model, state, album)
    val (coverWidth, coverHeight) =
        fittedMusicArtwork(258.dp, 258.dp, album.primaryImageAspectRatio ?: 1f)
    CompositionLocalProvider(LocalMusicPlayerPlus provides true) {
        MusicPage(albumTitle, largeTitle = false) {
            item {
                Spacer(Modifier.height(16.dp))
                if (tabletMode) {
                    Row(
                        Modifier.fillMaxWidth().height(coverHeight),
                        verticalAlignment = Alignment.Top,
                    ) {
                        MusicArtwork(
                            model,
                            state,
                            album,
                            Modifier.size(coverWidth, coverHeight),
                            9.dp,
                            sharedKey =
                                LocalArtworkNavigation.current?.origins?.get(album.id)?.token?.let {
                                    if (it.startsWith("container:")) "cover:$it" else it
                                },
                            shadowElevation = 12.dp,
                        )
                        Spacer(Modifier.width(28.dp))
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Column {
                                    MusicText(
                                        albumTitle,
                                        28,
                                        Color.White,
                                        FontWeight.Bold,
                                        modifier = Modifier.fillMaxWidth(),
                                        lines = 2,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    AlbumArtistLink(model, state, album) {
                                        MusicText(
                                            album.albumArtistDisplay,
                                            20,
                                            Color.White,
                                            modifier = Modifier.fillMaxWidth(),
                                            lines = 2,
                                        )
                                    }
                                    Spacer(Modifier.height(7.dp))
                                    MusicText(
                                        listOfNotNull(album.genre, album.year?.toString())
                                            .joinToString(" · "),
                                        14,
                                        Color.White.copy(alpha = .65f),
                                        FontWeight.Medium,
                                    )
                                }
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Box(
                                        Modifier.size(48.dp)
                                            .clip(CircleShape)
                                            .musicBackground(Color.White.copy(alpha = .12f))
                                            .clickable {
                                                viewModel.accept(AlbumIntent.Play(songs.shuffled()))
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MusicIcon("shuffle", Color.White, 25.dp)
                                    }
                                    Row(
                                        Modifier.width(160.dp)
                                            .height(48.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .clickable { viewModel.accept(AlbumIntent.Play(songs)) }
                                            .semantics { contentDescription = text_play_album },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        MusicIcon("play", albumAccent, 20.dp)
                                        Spacer(Modifier.width(7.dp))
                                        MusicText(text_play, 18, albumAccent, FontWeight.Bold)
                                    }
                                    AlbumDownloadButton(model, state, album, songs)
                                }
                                if (!album.overview.isNullOrBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    MusicAlbumOverview(album.id, albumTitle, album.overview)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MusicArtwork(
                            model,
                            state,
                            album,
                            Modifier.size(coverWidth, coverHeight),
                            9.dp,
                            sharedKey =
                                LocalArtworkNavigation.current?.origins?.get(album.id)?.token?.let {
                                    if (it.startsWith("container:")) "cover:$it" else it
                                },
                            shadowElevation = 12.dp,
                        )
                        Spacer(Modifier.height(24.dp))
                        MusicText(
                            albumTitle,
                            22,
                            Color.White,
                            FontWeight.Bold,
                            lines = 2,
                            align = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(5.dp))
                        AlbumArtistLink(model, state, album) {
                            MusicText(
                                album.albumArtistDisplay,
                                20,
                                Color.White,
                                lines = 2,
                                align = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        MusicText(
                            listOfNotNull(album.genre, album.year?.toString()).joinToString(" · "),
                            12,
                            Color.White.copy(alpha = .65f),
                            FontWeight.Medium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .musicBackground(Color.White.copy(alpha = .12f))
                                    .clickable {
                                        viewModel.accept(AlbumIntent.Play(songs.shuffled()))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                MusicIcon("shuffle", Color.White, 25.dp)
                            }
                            Row(
                                Modifier.width(160.dp)
                                    .height(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { viewModel.accept(AlbumIntent.Play(songs)) }
                                    .semantics { contentDescription = text_play_album },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                MusicIcon("play", albumAccent, 20.dp)
                                Spacer(Modifier.width(7.dp))
                                MusicText(text_play, 18, albumAccent, FontWeight.Bold)
                            }
                            AlbumDownloadButton(model, state, album, songs)
                        }
                    }
                    if (!album.overview.isNullOrBlank()) {
                        Spacer(Modifier.height(20.dp))
                        MusicAlbumOverview(album.id, albumTitle, album.overview)
                        Spacer(Modifier.height(20.dp))
                    } else Spacer(Modifier.height(24.dp))
                }
                if (tabletMode) Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .height(.5.dp)
                        .musicSwipeDivider(afterId = songs.firstOrNull()?.id)
                        .musicBackground(Color.White.copy(alpha = .15f))
                )
            }
            var offset = 0
            discs.forEach { (disc, tracks) ->
                val startIndex = offset
                if (multiDisc)
                    item(key = "disc:$disc") {
                        MusicText(
                            formatBellString(text_disc_number, disc),
                            17,
                            Color.White,
                            FontWeight.SemiBold,
                            Modifier.padding(top = 20.dp, bottom = 12.dp),
                        )
                    }
                itemsIndexed(tracks, key = { _, it -> it.id }) { index, item ->
                    MusicTrackRow(
                        model,
                        state,
                        item,
                        startIndex + index,
                        songs,
                        numbered = album.type != "Playlist",
                        subtitle =
                            if (album.type == "MusicAlbum") albumTrackArtist(item, album) else null,
                        numberLabel = item.number ?: index + 1,
                        fullWidthDivider = index == tracks.lastIndex,
                    )
                }
                offset += tracks.size
            }
            item {
                Spacer(Modifier.height(18.dp))
                MusicText(
                    formatBellString(
                        text_album_track_summary,
                        songs.size,
                        songs.sumOf { it.durationMs } / 60000,
                    ),
                    15,
                    Color.White.copy(alpha = .5f),
                )
                val date = album.released?.take(10)?.split('-')
                val release =
                    if (date?.size == 3 && date.all { it.toIntOrNull() != null })
                        formatBellString(dateFormat, date[0], date[1].toInt(), date[2].toInt())
                    else album.year?.toString()
                release?.let { MusicText(it, 15, Color.White.copy(alpha = .5f)) }
            }
        }
    }
}
