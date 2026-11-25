// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

object Routes {
    const val Home = "route:home"
    const val Library = "route:library"
    const val Search = "route:search"
    const val Albums = "route:albums"
    const val Playlists = "route:playlists"
    const val Artists = "route:artists"
    const val Songs = "route:songs"
    const val Favorites = "route:favorites"
    const val FavoriteSongs = "route:favoritesongs"
    const val Settings = "route:settings"
    const val Recent = "route:recent"
    const val ArtistPrefix = "route:artistprefix"
    const val Downloaded = "route:downloaded"
    const val DownloadedAlbums = "route:downloaded/albums"
    const val DownloadedArtists = "route:downloaded/artists"
    const val DownloadedPlaylists = "route:downloaded/playlists"
    const val DownloadedFavoriteSongs = "route:downloaded/favorites"
    const val DownloadedSongs = "route:downloaded/songs"

    fun isDownloaded(route: String) = route.startsWith(Downloaded)

    fun isFavoriteSongs(route: String) =
        route == Favorites || route == FavoriteSongs || route == DownloadedFavoriteSongs

    fun downloaded(route: String) =
        when (route) {
            Albums -> DownloadedAlbums
            Artists -> DownloadedArtists
            Playlists -> DownloadedPlaylists
            Songs -> DownloadedSongs
            FavoriteSongs -> DownloadedFavoriteSongs
            else -> route
        }
}
