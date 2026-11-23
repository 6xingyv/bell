// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.artist

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
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.MusicTrackRow
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@Composable
fun ArtistScreen(model: BellStore, state: BellState) {
    val offline = LocalMusicOffline.current

    val viewModel = rememberScreenViewModel(model) { ArtistViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    val text_albums = stringResource(Res.string.albums)
    val text_latest_album = stringResource(Res.string.latest_album)
    val text_no_music = stringResource(Res.string.no_music)
    val text_popular_songs = stringResource(Res.string.popular_songs)
    val text_artist_music_empty = stringResource(Res.string.artist_music_empty)

    val artist = screenState.artist ?: return
    val tracks = screenState.songs
    val albums = screenState.albums
    MusicPage(artist.name, largeTitle = false, horizontalInset = 0.dp, underHeader = true) {
        item(key = "artist-hero") {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                MusicArtwork(model, state, artist, Modifier.fillMaxSize(), 0.dp)
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .65f)),
                                startY = 0f,
                            )
                        )
                )
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicText(
                        artist.name,
                        34,
                        Color.White,
                        FontWeight.Bold,
                        Modifier.weight(1f),
                        lines = 2,
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(MusicAccent).clickable {
                            viewModel.accept(ArtistIntent.Play(tracks))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicIcon("play", Color.White, 24.dp)
                    }
                }
            }
        }
        albums.firstOrNull()?.let { latest ->
            item(key = "artist-latest") {
                val link =
                    rememberArtworkLink(latest.id) {
                        model.accept(BellIntent.OpenAlbum(latest, offline))
                    }
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp)
                        .clickable(onClick = link.open),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicArtwork(
                        model,
                        state,
                        latest,
                        Modifier.size(96.dp),
                        7.dp,
                        sharedKey = link.key,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        MusicText(
                            latest.released?.take(10)
                                ?: latest.year?.toString()
                                ?: text_latest_album,
                            12,
                            Color.Gray,
                            FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(5.dp))
                        MusicText(latest.name, 18, lines = 2)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF1F1F3))
                                .clickable { model.accept(BellIntent.Favorite(latest)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            MusicIcon(
                                if (latest.userData.favorite) "star.fill" else "star",
                                MusicAccent,
                                19.dp,
                            )
                        }
                    }
                }
            }
        }
        if (tracks.isNotEmpty())
            item(key = "artist-top-songs") {
                MusicText(
                    text_popular_songs,
                    24,
                    weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tracks.take(30).chunked(3)) { page ->
                        Column(Modifier.width(325.dp)) {
                            page.forEach { track ->
                                MusicTrackRow(
                                    model,
                                    state,
                                    track,
                                    tracks.indexOfFirst { it.id == track.id },
                                    tracks,
                                    swipeEnabled = false,
                                    subtitle =
                                        listOfNotNull(track.album, track.year?.toString())
                                            .joinToString(" · "),
                                )
                            }
                        }
                    }
                }
            }
        if (albums.isNotEmpty())
            item(key = "artist-albums") {
                Spacer(Modifier.height(24.dp))
                MusicText(
                    text_albums,
                    24,
                    weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { it.id }) { album ->
                        AlbumTile(model, state, album, Modifier.width(156.dp))
                    }
                }
            }
        if (tracks.isEmpty() && albums.isEmpty() && !state.loading)
            item { EmptyMusic(text_no_music, text_artist_music_empty) }
    }
}
