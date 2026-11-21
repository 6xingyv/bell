// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.composables.AmbientArtwork
import com.mocharealm.bell.ui.composables.BellBackHandler
import com.mocharealm.bell.ui.composables.BellTheme
import com.mocharealm.bell.ui.composables.LocalMusicArtworkColors
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicHeader
import com.mocharealm.bell.ui.composables.MusicHeaderAction
import com.mocharealm.bell.ui.composables.MusicLibraryDialog
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.MusicMenuSource
import com.mocharealm.bell.ui.composables.MusicQueueNotice
import com.mocharealm.bell.ui.composables.MusicSystemBars
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.collectionMenu
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.composables.rememberMusicSharer
import com.mocharealm.bell.ui.composables.songMenu
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.ArtworkNavigation
import com.mocharealm.bell.ui.nav.LocalArtworkNavigation
import com.mocharealm.bell.ui.nav.LocalMusicPlayerBackdropHost
import com.mocharealm.bell.ui.nav.LocalPlayerArtworkRole
import com.mocharealm.bell.ui.nav.LocalPlayerMotion
import com.mocharealm.bell.ui.nav.MusicNavigationHost
import com.mocharealm.bell.ui.nav.MusicPlayerBackdropHost
import com.mocharealm.bell.ui.nav.NavigationIntent
import com.mocharealm.bell.ui.nav.NavigationViewModel
import com.mocharealm.bell.ui.nav.PlayerArtworkRole
import com.mocharealm.bell.ui.nav.PlayerMotion
import com.mocharealm.bell.ui.nav.Routes
import com.mocharealm.bell.ui.nav.playerDrag
import com.mocharealm.bell.ui.nav.playerPanelShape
import com.mocharealm.bell.ui.nav.playerWindowCorners
import com.mocharealm.bell.ui.nav.routeTitle
import com.mocharealm.bell.ui.screen.album.AlbumScreen
import com.mocharealm.bell.ui.screen.albums.AlbumsScreen
import com.mocharealm.bell.ui.screen.artist.ArtistScreen
import com.mocharealm.bell.ui.screen.artists.ArtistsScreen
import com.mocharealm.bell.ui.screen.credits.CreditsScreen
import com.mocharealm.bell.ui.screen.downloaded.DownloadedScreen
import com.mocharealm.bell.ui.screen.home.HomeScreen
import com.mocharealm.bell.ui.screen.library.LibraryScreen
import com.mocharealm.bell.ui.screen.login.LoginScreen
import com.mocharealm.bell.ui.screen.player.PlayerIntent
import com.mocharealm.bell.ui.screen.player.PlayerPanel
import com.mocharealm.bell.ui.screen.player.PlayerScreen
import com.mocharealm.bell.ui.screen.player.PlayerViewModel
import com.mocharealm.bell.ui.screen.player.composables.MusicMiniPlayer
import com.mocharealm.bell.ui.screen.player.composables.MusicPlayerSurface
import com.mocharealm.bell.ui.screen.player.composables.MusicPlayingArtworkOverlay
import com.mocharealm.bell.ui.screen.player.composables.recordMiniPlayerContent
import com.mocharealm.bell.ui.screen.player.composables.recordPlayerSurface
import com.mocharealm.bell.ui.screen.playlists.PlaylistsScreen
import com.mocharealm.bell.ui.screen.search.SearchScreen
import com.mocharealm.bell.ui.screen.settings.SettingsScreen
import com.mocharealm.bell.ui.screen.songs.SongsScreen
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import com.mocharealm.bell.ui.utils.displayText
import com.mocharealm.bell.ui.utils.downloadedState
import com.mocharealm.bell.ui.utils.formatBellString
import com.mocharealm.bell.ui.utils.rememberMenuStrings
import org.jetbrains.compose.resources.stringResource

@Composable
fun BellApp(model: BellStore) {
    val mixTitle = stringResource(Res.string.mix_title)

    val routeTitles =
        listOf(
                Routes.Home,
                Routes.Library,
                Routes.Search,
                Routes.Albums,
                Routes.Playlists,
                Routes.Artists,
                Routes.Songs,
                Routes.Favorites,
                Routes.FavoriteSongs,
                Routes.Settings,
                Routes.Recent,
                Routes.Downloaded,
                Routes.DownloadedAlbums,
                Routes.DownloadedArtists,
                Routes.DownloadedPlaylists,
                Routes.DownloadedSongs,
                Routes.DownloadedFavoriteSongs,
            )
            .associateWith { routeTitle(it) }

    val menuStrings = rememberMenuStrings()

    val text_account = stringResource(Res.string.account)
    val text_create_playlist = stringResource(Res.string.create_playlist)
    val text_create_station = stringResource(Res.string.create_station)
    val text_favorite_artist = stringResource(Res.string.favorite_artist)
    val text_more = stringResource(Res.string.more)
    val text_name_ascending = stringResource(Res.string.name_ascending)
    val text_name_descending = stringResource(Res.string.name_descending)
    val text_play_all = stringResource(Res.string.play_all)
    val text_refresh_library = stringResource(Res.string.refresh_library)
    val text_settings = stringResource(Res.string.settings)
    val text_share = stringResource(Res.string.share)
    val text_share_artist = stringResource(Res.string.share_artist)
    val text_shuffle = stringResource(Res.string.shuffle)
    val text_songs = stringResource(Res.string.songs)
    val text_sort = stringResource(Res.string.sort)
    val text_suggest_less = stringResource(Res.string.suggest_less)

    val state by model.state.collectAsState()
    val navigator = remember { NavigationViewModel() }
    val navigation by navigator.state.collectAsState()
    val tab = navigation.tab
    val route = navigation.route
    val scope = rememberCoroutineScope()
    val playerMotion = remember(scope) { PlayerMotion(scope) }
    val playerViewModel = rememberScreenViewModel(model) { PlayerViewModel(model) }
    val miniLayer = rememberGraphicsLayer()
    val miniContentLayer = rememberGraphicsLayer()
    val playerLayer = rememberGraphicsLayer()
    val windowCorners = playerWindowCorners()
    LaunchedEffect(state.current?.id) { if (state.current == null) playerMotion.settle(false) }
    val artworkNavigation = remember { ArtworkNavigation() }
    val artworkColors = remember { mutableStateMapOf<String, Color>() }
    val playerBackdrop = remember { MusicPlayerBackdropHost() }
    val pageSnapshots = remember { mutableMapOf<String, BellState>() }
    val creditsDark = isSystemInDarkTheme()
    val pageKey =
        state.creditsItem?.let { "credits:${it.id}" }
            ?: state.album?.let { "album:${it.id}${if(state.albumOffline)":offline"else""}" }
            ?: route
    pageSnapshots[pageKey] = state
    LaunchedEffect(pageKey, playerMotion.visible, state.session) {
        model.accept(BellIntent.HomeVisibility(pageKey == Routes.Home && !playerMotion.visible))
    }
    val albums =
        remember(state.albums, state.songs) {
            state.albums.ifEmpty {
                state.songs
                    .filter { !it.album.isNullOrBlank() }
                    .groupBy { it.album }
                    .map { (name, songs) ->
                        Item(
                            id = "jellify-album:$name",
                            name = name.orEmpty(),
                            type = "MusicAlbum",
                            albumArtist = songs.first().artist,
                            ticks = songs.sumOf { it.ticks },
                        )
                    }
            }
        }
    fun navigate(next: String) {
        model.accept(BellIntent.CloseCredits)
        navigator.accept(NavigationIntent.Navigate(next))
        model.accept(BellIntent.CloseAlbum)
    }
    val share = rememberMusicSharer()
    BellTheme {
        MusicSystemBars(
            playerMotion.progress > .5f ||
                creditsDark ||
                (state.album != null && state.album?.type != "MusicArtist")
        )
        when {
            state.session == null -> LoginScreen(state, model)
            else ->
                Box {
                    CompositionLocalProvider(
                        LocalMusicArtworkColors provides artworkColors,
                        LocalMusicPlayerBackdropHost provides playerBackdrop,
                        LocalArtworkNavigation provides artworkNavigation,
                        LocalPlayerMotion provides playerMotion,
                    ) {
                        MusicMenuSource {
                            BoxWithConstraints(
                                Modifier.fillMaxSize().onGloballyPositioned {
                                    playerMotion.screenBounds = it.boundsInRoot()
                                }
                            ) {
                                val tabletMode = maxWidth >= 600.dp
                                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
                                val velocityThreshold = with(LocalDensity.current) { 700.dp.toPx() }
                                Box(
                                    Modifier.fillMaxSize()
                                        .drawWithContent {
                                            // The full player is opaque. Keep navigation state and
                                            // layout,
                                            // but do not replay its images and glass behind every
                                            // lyric frame.
                                            if (playerMotion.progress < 1f) drawContent()
                                        }
                                        .graphicsLayer {
                                            val scale = 1f - .1f * playerMotion.progress
                                            scaleX = scale
                                            scaleY = scale
                                            shape = playerPanelShape(1f, windowCorners)
                                            clip = playerMotion.visible
                                        }
                                ) {
                                    MusicNavigationHost(
                                        route = pageKey,
                                        selectedTab = tab,
                                        model = model,
                                        header = { key ->
                                            val rawSnapshot = pageSnapshots[key] ?: state
                                            val offline =
                                                Routes.isDownloaded(key) ||
                                                    rawSnapshot.albumOffline &&
                                                        key.startsWith("album:")
                                            val snapshot =
                                                if (offline) downloadedState(rawSnapshot)
                                                else rawSnapshot
                                            val credit =
                                                snapshot.creditsItem.takeIf {
                                                    key.startsWith("credits:")
                                                }
                                            val album =
                                                snapshot.album.takeIf { key.startsWith("album:") }
                                            val tracks =
                                                when {
                                                    key in
                                                        listOf(
                                                            Routes.Favorites,
                                                            Routes.FavoriteSongs,
                                                            Routes.DownloadedFavoriteSongs,
                                                        ) ->
                                                        snapshot.songs.filter {
                                                            it.userData.favorite
                                                        }
                                                    key.startsWith(Routes.ArtistPrefix) ->
                                                        snapshot.songs.filter {
                                                            key.removePrefix(Routes.ArtistPrefix) in
                                                                it.artists
                                                        }
                                                    else -> snapshot.songs
                                                }
                                            val menu =
                                                if (
                                                    key == Routes.Songs ||
                                                        key == Routes.DownloadedSongs ||
                                                        key == Routes.FavoriteSongs ||
                                                        key == Routes.DownloadedFavoriteSongs ||
                                                        key.startsWith(Routes.ArtistPrefix)
                                                )
                                                    listOf(
                                                        MusicMenuAction(text_play_all, "play") {
                                                            model.accept(BellIntent.Play(tracks))
                                                        },
                                                        MusicMenuAction(text_shuffle, "shuffle") {
                                                            model.accept(
                                                                BellIntent.Play(tracks.shuffled())
                                                            )
                                                        },
                                                    )
                                                else
                                                    listOf(
                                                        MusicMenuAction(
                                                            text_refresh_library,
                                                            "repeat",
                                                        ) {
                                                            model.accept(
                                                                if (offline)
                                                                    BellIntent.RefreshOffline
                                                                else BellIntent.Refresh()
                                                            )
                                                        },
                                                        MusicMenuAction(text_songs, "songs") {
                                                            navigate(
                                                                if (offline) Routes.DownloadedSongs
                                                                else Routes.Songs
                                                            )
                                                        },
                                                        MusicMenuAction(text_account, "artist") {
                                                            navigate(Routes.Settings)
                                                        },
                                                    )
                                            MusicHeader(
                                                title =
                                                    credit?.name
                                                        ?: album?.let {
                                                            if (it.type == "Mix")
                                                                formatBellString(mixTitle, it.name)
                                                            else it.name
                                                        }
                                                        ?: key.removePrefix(Routes.ArtistPrefix)
                                                            .let {
                                                                if (it == Routes.Settings)
                                                                    text_account
                                                                else routeTitles[it] ?: it
                                                            },
                                                root =
                                                    key in
                                                        listOf(
                                                            Routes.Home,
                                                            Routes.Library,
                                                            Routes.Search,
                                                        ),
                                                dark =
                                                    creditsDark ||
                                                        (album != null &&
                                                            album.type != "MusicArtist"),
                                                largeTitle = album == null && credit == null,
                                                actions =
                                                    if (credit != null)
                                                        listOf(
                                                            MusicHeaderAction(
                                                                "more",
                                                                "ellipsis",
                                                                text_more,
                                                                menu =
                                                                    songMenu(
                                                                            menuStrings,
                                                                            model,
                                                                            snapshot,
                                                                            credit,
                                                                            share,
                                                                        )
                                                                        .filterNot {
                                                                            it.title ==
                                                                                menuStrings[
                                                                                    Res.string
                                                                                        .show_credits]
                                                                        },
                                                            )
                                                        )
                                                    else if (album?.type == "MusicArtist")
                                                        listOf(
                                                            MusicHeaderAction(
                                                                "favorite",
                                                                if (album.userData.favorite)
                                                                    "star.fill"
                                                                else "star",
                                                                text_favorite_artist,
                                                                {
                                                                    model.accept(
                                                                        BellIntent.Favorite(album)
                                                                    )
                                                                },
                                                            ),
                                                            MusicHeaderAction(
                                                                "more",
                                                                "ellipsis",
                                                                text_more,
                                                                menu =
                                                                    listOf(
                                                                        MusicMenuAction(
                                                                            text_create_station,
                                                                            "radio",
                                                                        ) {
                                                                            model.accept(
                                                                                BellIntent.Station(
                                                                                    album
                                                                                )
                                                                            )
                                                                        },
                                                                        MusicMenuAction(
                                                                            text_share_artist,
                                                                            "share",
                                                                        ) {
                                                                            share(
                                                                                "${state.session?.server}/web/#/details?id=${album.id}"
                                                                            )
                                                                        },
                                                                        MusicMenuAction(
                                                                            text_suggest_less,
                                                                            "dislike",
                                                                        ) {
                                                                            model.accept(
                                                                                BellIntent
                                                                                    .SuggestLess(
                                                                                        album
                                                                                    )
                                                                            )
                                                                        },
                                                                    ),
                                                            ),
                                                        )
                                                    else if (album != null)
                                                        listOf(
                                                            MusicHeaderAction(
                                                                "share",
                                                                "share",
                                                                text_share,
                                                                {
                                                                    share(
                                                                        "${state.session?.server}/web/#/details?id=${album.id}"
                                                                    )
                                                                },
                                                            ),
                                                            MusicHeaderAction(
                                                                "more",
                                                                "ellipsis",
                                                                text_more,
                                                                menu =
                                                                    collectionMenu(
                                                                        menuStrings,
                                                                        model,
                                                                        state,
                                                                        album,
                                                                        snapshot.albumSongs,
                                                                        share,
                                                                    ),
                                                            ),
                                                        )
                                                    else if (
                                                        key == Routes.Artists ||
                                                            key == Routes.DownloadedArtists
                                                    )
                                                        listOf(
                                                            MusicHeaderAction(
                                                                "sort",
                                                                "sort",
                                                                text_sort,
                                                                menu =
                                                                    listOf(
                                                                        MusicMenuAction(
                                                                            text_name_ascending,
                                                                            if (
                                                                                !state
                                                                                    .artistDescending
                                                                            )
                                                                                "check"
                                                                            else "sort",
                                                                        ) {
                                                                            model.accept(
                                                                                BellIntent
                                                                                    .ArtistSort(
                                                                                        false
                                                                                    )
                                                                            )
                                                                        },
                                                                        MusicMenuAction(
                                                                            text_name_descending,
                                                                            if (
                                                                                state
                                                                                    .artistDescending
                                                                            )
                                                                                "check"
                                                                            else "sort",
                                                                        ) {
                                                                            model.accept(
                                                                                BellIntent
                                                                                    .ArtistSort(
                                                                                        true
                                                                                    )
                                                                            )
                                                                        },
                                                                    ),
                                                            )
                                                        )
                                                    else
                                                        listOfNotNull(
                                                            if (key == Routes.Playlists)
                                                                MusicHeaderAction(
                                                                    "add",
                                                                    "plus",
                                                                    text_create_playlist,
                                                                    {
                                                                        model.accept(
                                                                            BellIntent.Dialog(
                                                                                LibraryDialog
                                                                                    .CreatePlaylist()
                                                                            )
                                                                        )
                                                                    },
                                                                )
                                                            else null,
                                                            if (
                                                                key !in
                                                                    listOf(
                                                                        Routes.Home,
                                                                        Routes.Search,
                                                                    )
                                                            )
                                                                MusicHeaderAction(
                                                                    "more",
                                                                    "ellipsis",
                                                                    text_more,
                                                                    menu = menu,
                                                                )
                                                            else null,
                                                            if (
                                                                key in
                                                                    listOf(
                                                                        Routes.Library,
                                                                        Routes.Home,
                                                                        Routes.Search,
                                                                    )
                                                            )
                                                                MusicHeaderAction(
                                                                    "account",
                                                                    "artist",
                                                                    text_account,
                                                                    { navigate(Routes.Settings) },
                                                                    group = "account",
                                                                    avatarUrl =
                                                                        state.session?.let {
                                                                            model.repository.avatar(
                                                                                it,
                                                                                state.userImageTag,
                                                                            )
                                                                        },
                                                                    avatarInitial =
                                                                        state.session
                                                                            ?.user
                                                                            ?.name
                                                                            ?.take(1)
                                                                            .orEmpty(),
                                                                )
                                                            else null,
                                                        ),
                                            )
                                        },
                                        onBack = { key ->
                                            if (key.startsWith("credits:")) {
                                                pageSnapshots[key]?.creditsItem?.let {
                                                    model.accept(BellIntent.Credits(it))
                                                }
                                            } else if (key.startsWith("album:")) {
                                                pageSnapshots[key]?.album?.let {
                                                    model.accept(
                                                        BellIntent.OpenAlbum(
                                                            it,
                                                            pageSnapshots[key]?.albumOffline ==
                                                                true,
                                                        )
                                                    )
                                                }
                                            } else navigate(key)
                                        },
                                        onTab = {
                                            model.accept(BellIntent.CloseCredits)
                                            navigator.accept(NavigationIntent.SelectTab(it))
                                            model.accept(BellIntent.CloseAlbum)
                                        },
                                        onSearch = { navigate(Routes.Search) },
                                        background = { key ->
                                            val snapshot = pageSnapshots[key] ?: state
                                            CompositionLocalProvider(
                                                LocalMusicOffline provides snapshot.albumOffline
                                            ) {
                                                if (key.startsWith("credits:"))
                                                    Box(
                                                        Modifier.fillMaxSize()
                                                            .background(
                                                                if (creditsDark) Color.Black
                                                                else Color(0xFFF2F2F7)
                                                            )
                                                    )
                                                else if (
                                                    key.startsWith("album:") &&
                                                        snapshot.album != null &&
                                                        snapshot.album.type != "MusicArtist"
                                                )
                                                    AmbientArtwork(model, snapshot, snapshot.album)
                                                else
                                                    Box(
                                                        Modifier.fillMaxSize()
                                                            .background(
                                                                if (creditsDark) Color.Black
                                                                else Color.White
                                                            )
                                                    )
                                            }
                                        },
                                        miniPlayer = {
                                            CompositionLocalProvider(
                                                LocalPlayerArtworkRole provides
                                                    PlayerArtworkRole.Mini
                                            ) {
                                                MusicMiniPlayer(
                                                    model,
                                                    state,
                                                    Modifier.onGloballyPositioned {
                                                            if (playerMotion.progress == 0f)
                                                                playerMotion.miniBounds =
                                                                    it.boundsInRoot()
                                                        }
                                                        .recordPlayerSurface(miniLayer) {
                                                            playerMotion.progress == 0f
                                                        }
                                                        .then(
                                                            if (state.current != null)
                                                                Modifier.playerDrag(
                                                                    playerMotion,
                                                                    heightPx,
                                                                    velocityThreshold,
                                                                )
                                                            else Modifier
                                                        ),
                                                    transitionContent =
                                                        Modifier.recordMiniPlayerContent(
                                                            miniContentLayer
                                                        ),
                                                    tabletMode = tabletMode,
                                                    onPanel = { panel ->
                                                        playerViewModel.accept(
                                                            PlayerIntent.SelectPanel(panel)
                                                        )
                                                        playerViewModel.accept(
                                                            PlayerIntent.RevealControls
                                                        )
                                                        playerMotion.settle(true)
                                                    },
                                                ) {
                                                    if (state.current != null)
                                                        playerMotion.settle(true)
                                                }
                                            }
                                        },
                                        content = { key ->
                                            val rawPageState =
                                                if (key == pageKey) state
                                                else pageSnapshots[key] ?: state
                                            val offline =
                                                Routes.isDownloaded(key) ||
                                                    key.startsWith("album:") &&
                                                        rawPageState.albumOffline
                                            val pageState =
                                                if (offline) downloadedState(rawPageState)
                                                else rawPageState
                                            val pageRoute =
                                                if (key.startsWith("album:")) route else key
                                            CompositionLocalProvider(
                                                LocalMusicOffline provides offline,
                                                LocalMusicDark provides
                                                    (creditsDark ||
                                                        (key.startsWith("album:") &&
                                                            pageState.album?.type !=
                                                                "MusicArtist")),
                                            ) {
                                                when {
                                                    key.startsWith("credits:") ->
                                                        CreditsScreen(model, pageState) {
                                                            if (
                                                                playerViewModel.presentation.value
                                                                    .panel != PlayerPanel.Lyrics
                                                            )
                                                                playerViewModel.accept(
                                                                    PlayerIntent.SelectPanel(
                                                                        PlayerPanel.Lyrics
                                                                    )
                                                                )
                                                            playerViewModel.accept(
                                                                PlayerIntent.RevealControls
                                                            )
                                                            playerMotion.settle(true)
                                                        }
                                                    key.startsWith("album:") &&
                                                        pageState.album?.type == "MusicArtist" ->
                                                        ArtistScreen(model, pageState)
                                                    key.startsWith("album:") &&
                                                        pageState.album != null ->
                                                        AlbumScreen(
                                                            model,
                                                            pageState,
                                                            { model.accept(BellIntent.CloseAlbum) },
                                                        )
                                                    pageRoute == Routes.Library ->
                                                        LibraryScreen(
                                                            model,
                                                            pageState,
                                                            albums,
                                                            ::navigate,
                                                        )
                                                    pageRoute == Routes.Downloaded ->
                                                        DownloadedScreen(
                                                            model,
                                                            rawPageState,
                                                            ::navigate,
                                                        )
                                                    pageRoute == Routes.Albums ||
                                                        pageRoute == Routes.DownloadedAlbums ->
                                                        AlbumsScreen(
                                                            model,
                                                            pageState,
                                                            if (offline) pageState.albums
                                                            else albums,
                                                            { navigate(Routes.Library) },
                                                        )
                                                    pageRoute == Routes.Playlists ||
                                                        pageRoute == Routes.DownloadedPlaylists ->
                                                        PlaylistsScreen(
                                                            model,
                                                            pageState,
                                                            {
                                                                navigate(
                                                                    if (offline) Routes.Downloaded
                                                                    else Routes.Library
                                                                )
                                                            },
                                                            {
                                                                navigate(
                                                                    if (offline)
                                                                        Routes.downloaded(it)
                                                                    else it
                                                                )
                                                            },
                                                        )
                                                    pageRoute == Routes.Artists ||
                                                        pageRoute == Routes.DownloadedArtists ->
                                                        ArtistsScreen(model, pageState)
                                                    pageRoute == Routes.Search ->
                                                        SearchScreen(model, pageState)
                                                    pageRoute == Routes.Settings ->
                                                        SettingsScreen(
                                                            model,
                                                            pageState,
                                                            { navigate(Routes.Library) },
                                                        )
                                                    pageRoute == Routes.Home ||
                                                        pageRoute == Routes.Recent ->
                                                        HomeScreen(
                                                            model,
                                                            pageState,
                                                            albums,
                                                            routeTitle(pageRoute),
                                                            ::navigate,
                                                        )
                                                    else -> {
                                                        val songs =
                                                            when {
                                                                pageRoute in
                                                                    listOf(
                                                                        Routes.Favorites,
                                                                        Routes.FavoriteSongs,
                                                                        Routes
                                                                            .DownloadedFavoriteSongs,
                                                                    ) ->
                                                                    pageState.songs.filter {
                                                                        it.userData.favorite
                                                                    }
                                                                pageRoute.startsWith(
                                                                    Routes.ArtistPrefix
                                                                ) ->
                                                                    pageState.songs.filter {
                                                                        pageRoute.removePrefix(
                                                                            Routes.ArtistPrefix
                                                                        ) in it.artists
                                                                    }
                                                                else -> pageState.songs
                                                            }
                                                        SongsScreen(
                                                            model,
                                                            pageState,
                                                            songs,
                                                            routeTitle(
                                                                pageRoute.removePrefix(
                                                                    Routes.ArtistPrefix
                                                                )
                                                            ),
                                                            null,
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                                if (playerMotion.visible && state.current != null) {
                                    CompositionLocalProvider(
                                        LocalPlayerArtworkRole provides PlayerArtworkRole.Expanded
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().recordPlayerSurface(
                                                playerLayer
                                            ) {
                                                playerMotion.progress == 1f
                                            }
                                        ) {
                                            PlayerScreen(
                                                model,
                                                state,
                                                { playerMotion.settle(false) },
                                                playerViewModel,
                                                Modifier.playerDrag(
                                                    playerMotion,
                                                    heightPx,
                                                    velocityThreshold,
                                                ),
                                            )
                                        }
                                    }
                                    MusicPlayerSurface(
                                        playerMotion,
                                        miniContentLayer,
                                        playerLayer,
                                        windowCorners,
                                    )
                                    MusicPlayingArtworkOverlay(model, state, playerMotion)
                                }
                                BellBackHandler(playerMotion.visible) {
                                    playerMotion.settle(false)
                                }
                                CompositionLocalProvider(
                                    LocalMusicDark provides (playerMotion.progress > .5f)
                                ) {
                                    MusicLibraryDialog(model, state)
                                }
                                MusicQueueNotice(
                                    model,
                                    Modifier.align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(bottom = 190.dp),
                                )
                                if (
                                    state.error != null &&
                                        !Routes.isDownloaded(route) &&
                                        !state.albumOffline
                                )
                                    Box(
                                        Modifier.align(Alignment.TopCenter)
                                            .statusBarsPadding()
                                            .padding(20.dp)
                                            .clip(musicShape(12.dp))
                                            .background(Color(0xFFFFE8EC))
                                            .clickable { model.accept(BellIntent.ClearError) }
                                            .padding(12.dp)
                                    ) {
                                        MusicText(
                                            state.error?.displayText().orEmpty(),
                                            14,
                                            MusicAccent,
                                            lines = 3,
                                        )
                                    }
                            }
                        }
                    }
                }
        }
    }
}
