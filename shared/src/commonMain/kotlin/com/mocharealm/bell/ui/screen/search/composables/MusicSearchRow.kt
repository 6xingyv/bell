// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.search.composables

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
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicDownloadMarker
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.MusicMenuStyle
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MusicSearchRow(model: BellStore, state: BellState, item: Item, history: Boolean) {
    val text_add_to_queue = stringResource(Res.string.add_to_queue)
    val text_albums = stringResource(Res.string.albums)
    val text_artists = stringResource(Res.string.artists)
    val text_favorites = stringResource(Res.string.favorites)
    val text_genre = stringResource(Res.string.genre)
    val text_music = stringResource(Res.string.music)
    val text_open = stringResource(Res.string.open)
    val text_playlists = stringResource(Res.string.playlists)
    val text_songs = stringResource(Res.string.songs)
    val text_unfavorite = stringResource(Res.string.unfavorite)

    val link = rememberArtworkLink(item.id) { model.accept(BellIntent.OpenItem(item, history)) }
    val label =
        when (item.type) {
            "Audio" -> text_songs
            "MusicAlbum" -> text_albums
            "Playlist" -> text_playlists
            "MusicArtist" -> text_artists
            "MusicGenre" -> text_genre
            else -> text_music
        }
    Row(
        Modifier.fillMaxWidth().height(72.dp).clickable(onClick = link.open),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicArtwork(
            model,
            state,
            item,
            Modifier.size(56.dp),
            if (item.type == "MusicArtist") 28.dp else 5.dp,
            sharedKey = link.key,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MusicText(item.name, 17)
            MusicText(
                listOf(label, item.artist).filter { it.isNotBlank() }.joinToString(" · "),
                13,
                Color.Gray,
            )
        }
        if (item.type == "Audio") MusicDownloadMarker(model, state, item, Color.Gray)
        MusicMenu(
            buildList {
                add(
                    MusicMenuAction(text_open, "play") {
                        model.accept(BellIntent.OpenItem(item, history))
                    }
                )
                add(
                    MusicMenuAction(
                        if (item.userData.favorite) text_unfavorite else text_favorites,
                        if (item.userData.favorite) "star.fill" else "star",
                    ) {
                        model.accept(BellIntent.Favorite(item))
                    }
                )
                if (item.type == "Audio")
                    add(
                        MusicMenuAction(text_add_to_queue, "queue") {
                            model.accept(BellIntent.Enqueue(item))
                        }
                    )
            },
            Modifier.size(40.dp),
            MusicMenuStyle.Plain,
        )
    }
    Box(
        Modifier.padding(start = 68.dp)
            .fillMaxWidth()
            .height(.5.dp)
            .background(Color.Black.copy(alpha = .1f))
    )
}
