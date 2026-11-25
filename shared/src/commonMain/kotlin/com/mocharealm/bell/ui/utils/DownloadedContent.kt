// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf
import com.mocharealm.bell.domain.repository.SavedLibrary
import com.mocharealm.bell.ui.BellState

val LocalMusicOffline = staticCompositionLocalOf { false }

fun downloadedState(state: BellState): BellState {
    val catalog =
        state.offlineLibrary
            ?: SavedLibrary(
                state.albums.filter { album ->
                    state.songs.any { it.id in state.downloaded && it.albumId == album.id }
                },
                state.songs.filter { it.id in state.downloaded },
                state.playlists.filter { it.id in state.downloaded },
                state.artists.filter { artist ->
                    state.songs.any {
                        it.id in state.downloaded &&
                            (artist.name in it.artists || it.artistItems.any { it.id == artist.id })
                    }
                },
            )
    val songs = catalog.songs.map { it.id }.toSet()
    val albums = catalog.albums.map { it.id }.toSet()
    val playlists = catalog.playlists.map { it.id }.toSet()
    val artists = catalog.artists.map { it.id }.toSet()
    return state.copy(
        albums = catalog.albums,
        songs = catalog.songs,
        playlists = catalog.playlists,
        artists = catalog.artists,
        pins =
            state.pins.filter {
                it.id in songs || it.id in albums || it.id in playlists || it.id in artists
            },
        latest =
            state.latest
                .filter { it.id in albums }
                .ifEmpty { catalog.albums.sortedByDescending { it.created } },
        albumSongs = state.albumSongs.filter { it.id in songs },
        artistAlbums = state.artistAlbums.filter { it.id in albums },
        loading = false,
        error = null,
    )
}
