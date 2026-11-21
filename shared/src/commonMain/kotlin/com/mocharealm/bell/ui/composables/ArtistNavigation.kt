// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.utils.BellStrings
import com.mocharealm.bell.ui.utils.LocalMusicOffline

/** Artist identities come from server relations; group names containing & are never split. */
internal fun artistChoices(
    item: Item,
    state: BellState,
    albumArtists: Boolean,
    offline: Boolean = false,
): List<NamedItem> {
    val relations = if (albumArtists) item.albumArtists else item.artistItems
    val names = if (albumArtists) item.albumArtistNames else item.artists
    val known = if (offline) state.offlineLibrary?.artists.orEmpty() else state.artists
    return (relations.ifEmpty {
            names.map { name ->
                NamedItem(known.firstOrNull { it.name.equals(name, true) }?.id.orEmpty(), name)
            }
        })
        .filter { it.name.isNotBlank() }
        .distinctBy { it.id.ifBlank { it.name.lowercase() } }
}

private fun openArtist(
    model: BellStore,
    choice: NamedItem,
    offline: Boolean,
    leave: () -> Unit = {},
) {
    leave()
    if (choice.id.isNotBlank())
        model.accept(BellIntent.OpenAlbum(Item(choice.id, choice.name, "MusicArtist"), offline))
    else
        model.accept(
            BellIntent.GoToArtist(
                Item("artist-name:${choice.name}", artists = listOf(choice.name)),
                offline,
            )
        )
}

internal fun artistChoiceMenu(
    model: BellStore,
    choices: List<NamedItem>,
    offline: Boolean = false,
    leave: () -> Unit = {},
): List<MusicMenuAction> = choices.map { choice ->
    MusicMenuAction(
        choice.name,
        "music.microphone",
        hasAvatar = true,
        avatarUrl =
            choice.id
                .takeIf { it.isNotBlank() }
                ?.let { model.artwork(Item(it, choice.name, "MusicArtist"), 96, offline) },
    ) {
        openArtist(model, choice, offline, leave)
    }
}

@Composable
internal fun AlbumArtistLink(
    model: BellStore,
    state: BellState,
    album: Item,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offline = LocalMusicOffline.current
    val choices = artistChoices(album, state, true, offline)
    if (choices.size > 1)
        MusicTextMenu(artistChoiceMenu(model, choices, offline), modifier, content)
    else
        androidx.compose.foundation.layout.Box(
            modifier.clickable(enabled = choices.isNotEmpty()) {
                choices.firstOrNull()?.let { openArtist(model, it, offline) }
            }
        ) {
            content()
        }
}

internal fun playerArtistMenu(
    strings: BellStrings,
    model: BellStore,
    state: BellState,
    item: Item,
    leave: () -> Unit,
    offlineOnly: Boolean = false,
): List<MusicMenuAction> {
    val choices = artistChoices(item, state, false, offlineOnly)
    val selection = artistChoiceMenu(model, choices, offlineOnly, leave)
    val album =
        (if (offlineOnly) state.offlineLibrary?.albums.orEmpty() else state.albums).firstOrNull {
            it.id == item.albumId
        }
            ?: item.albumId?.let {
                Item(
                    it,
                    item.album.orEmpty(),
                    "MusicAlbum",
                    albumArtist = item.albumArtist,
                    albumArtists = item.albumArtists,
                )
            }
    return listOf(
        MusicMenuAction(
            strings[Res.string.go_to_artist],
            "music.microphone",
            enabled = choices.isNotEmpty(),
            subtitle = item.artist.takeIf { it.isNotBlank() },
            children = if (choices.size > 1) selection else emptyList(),
        ) {
            choices.firstOrNull()?.let { openArtist(model, it, offlineOnly, leave) }
        },
        MusicMenuAction(
            strings[Res.string.go_to_album],
            "albums",
            enabled = album != null,
            subtitle = item.album,
        ) {
            album?.let {
                leave()
                model.accept(BellIntent.OpenAlbum(it, offlineOnly))
            }
        },
    )
}
