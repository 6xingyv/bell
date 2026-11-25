// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.runtime.Composable
import com.mocharealm.bell.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun routeTitle(route: String): String =
    when (route) {
        Routes.DownloadedFavoriteSongs -> stringResource(Res.string.favorite_songs)
        Routes.Downloaded -> stringResource(Res.string.downloaded)
        Routes.DownloadedAlbums -> stringResource(Res.string.albums)
        Routes.DownloadedArtists -> stringResource(Res.string.artists)
        Routes.DownloadedPlaylists -> stringResource(Res.string.playlists)
        Routes.DownloadedSongs -> stringResource(Res.string.songs)
        Routes.Home -> stringResource(Res.string.home)
        Routes.Library -> stringResource(Res.string.library)
        Routes.Search -> stringResource(Res.string.search)
        Routes.Albums -> stringResource(Res.string.albums)
        Routes.Playlists -> stringResource(Res.string.playlists)
        Routes.Artists -> stringResource(Res.string.artists)
        Routes.Songs -> stringResource(Res.string.songs)
        Routes.Favorites -> stringResource(Res.string.favorites)
        Routes.FavoriteSongs -> stringResource(Res.string.favorite_songs)
        Routes.Settings -> stringResource(Res.string.account)
        Routes.Recent -> stringResource(Res.string.recent)
        else -> route
    }
