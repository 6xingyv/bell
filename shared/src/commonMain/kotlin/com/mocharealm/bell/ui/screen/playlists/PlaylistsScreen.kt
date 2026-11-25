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
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.CoverListRow
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.Routes
import com.mocharealm.bell.ui.nav.musicSharedArtwork
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.screen.library.composables.LibraryCollection
import com.mocharealm.bell.ui.screen.library.composables.libraryCollectionGrid
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlaylistsScreen(
    model: BellStore,
    state: BellState,
    back: () -> Unit,
    collection: LibraryCollection,
    navigate: (String) -> Unit,
) {
    val offline = LocalMusicOffline.current

    val text_favorite_songs = stringResource(Res.string.favorite_songs)
    val text_playlists = stringResource(Res.string.playlists)

    val viewModel = rememberScreenViewModel(model) { PlaylistsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)
    val playlists = collection.sorted(screenState.playlists)
    val grid = collection.grid
    val columns = LocalMusicGridColumns.current
    val favorites =
        remember(text_favorite_songs) {
            Item(Routes.FavoriteSongs, text_favorite_songs, "Playlist")
        }

    DetailList(text_playlists, back) {
        if (grid) {
            libraryCollectionGrid(listOf(favorites) + playlists, columns) { item, modifier ->
                if (item.id == favorites.id) {
                    val link =
                        rememberArtworkLink(favorites.id, container = false) {
                            navigate(Routes.FavoriteSongs)
                        }
                    Column(modifier.clickable(onClick = link.open)) {
                        FavoriteSongsCover(Modifier.fillMaxWidth().aspectRatio(1f), 9.dp, link.key)
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MusicText(item.name, 14, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(4.dp))
                            MusicIcon("star.fill", MusicAccent, 12.dp)
                        }
                    }
                } else {
                    AlbumTile(model, state, item, modifier, showArtist = false)
                }
            }
            return@DetailList
        }
        item {
            val link =
                rememberArtworkLink(favorites.id, container = false) {
                    navigate(Routes.FavoriteSongs)
                }
            CoverListRow(
                text_favorite_songs,
                null,
                { FavoriteSongsCover(Modifier.size(64.dp), 5.dp, link.key) },
                link.open,
            )
        }
        items(playlists, key = { it.id }) { item ->
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

@Composable
internal fun FavoriteSongsCover(
    modifier: Modifier,
    radius: Dp,
    sharedKey: String? = null,
    shadow: Dp = 0.dp,
) {
    val background = if (isSystemInDarkTheme()) Color(0xFF29292B) else Color(0xFFEEEEEF)
    val shape = musicShape(radius)
    BoxWithConstraints(
        modifier
            .then(musicSharedArtwork(sharedKey))
            .shadow(shadow, shape, clip = false)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        MusicIcon("star.fill", MusicAccent, maxWidth * .55f)
    }
}
