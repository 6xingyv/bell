// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.LibraryDialog
import com.mocharealm.bell.ui.utils.BellStrings
import com.mocharealm.bell.ui.utils.formatBellString
import org.jetbrains.compose.resources.stringResource

fun songMenu(
    strings: BellStrings,
    model: BellStore,
    state: BellState,
    item: Item,
    share: (String) -> Unit,
    onShareLyrics: (() -> Unit)? = null,
    leavePlayer: () -> Unit = {},
    offlineOnly: Boolean = false,
): List<MusicMenuAction> = buildList {
    add(
        MusicMenuAction(
            if (item.id in state.downloading) strings[Res.string.downloading]
            else if (item.id in state.downloaded) strings[Res.string.downloaded]
            else strings[Res.string.download],
            if (item.id in state.downloaded) "check" else "download",
            quick = true,
            enabled = item.id !in state.downloading,
        ) {
            model.accept(BellIntent.Download(item))
        }
    )
    add(
        MusicMenuAction(
            if (item.userData.favorite) strings[Res.string.unfavorite]
            else strings[Res.string.favorites],
            if (item.userData.favorite) "star.fill" else "star",
            quick = true,
        ) {
            model.accept(BellIntent.Favorite(item))
        }
    )
    add(
        MusicMenuAction(strings[Res.string.share], "share", quick = true) {
            share("${state.session?.server}/web/#/details?id=${item.id}")
        }
    )
    add(
        MusicMenuAction(
            if (state.pins.any { it.id == item.id }) strings[Res.string.unpin]
            else strings[Res.string.pin_song],
            "pin",
        ) {
            model.accept(BellIntent.Pin(item))
        }
    )
    add(
        MusicMenuAction(strings[Res.string.add_to_playlist], "playlist") {
            model.accept(BellIntent.Dialog(LibraryDialog.AddToPlaylist(listOf(item))))
        }
    )
    add(
        MusicMenuAction(strings[Res.string.create_station], "radio", separatorBefore = true) {
            model.accept(BellIntent.Station(item))
        }
    )
    val navigation = playerArtistMenu(strings, model, state, item, leavePlayer, offlineOnly)
    add(navigation[1].copy(separatorBefore = true))
    add(navigation[0])
    add(
        MusicMenuAction(strings[Res.string.show_credits], "info") {
            leavePlayer()
            model.accept(BellIntent.Credits(item))
        }
    )
    add(
        MusicMenuAction(
            strings[Res.string.share_lyrics],
            "shareLyrics",
            enabled = onShareLyrics != null,
        ) {
            onShareLyrics?.invoke()
        }
    )
    if (item.id in state.downloaded)
        add(
            MusicMenuAction(
                strings[Res.string.remove_download],
                "xmark.circle",
                separatorBefore = true,
                enabled = item.id !in state.downloading,
            ) {
                model.accept(BellIntent.RemoveDownload(item))
            }
        )
    add(
        MusicMenuAction(
            strings[Res.string.delete_from_library],
            "trash",
            separatorBefore = item.id !in state.downloaded,
            destructive = true,
            enabled = model.canDelete(item),
        ) {
            model.accept(BellIntent.Dialog(LibraryDialog.Delete(item)))
        }
    )
}

fun collectionMenu(
    strings: BellStrings,
    model: BellStore,
    state: BellState,
    item: Item,
    tracks: List<Item>,
    share: (String) -> Unit,
): List<MusicMenuAction> {
    val real = !item.id.startsWith("jellify-")
    val playlist = item.type == "Playlist"
    return buildList {
        add(
            MusicMenuAction(
                if (item.id in state.downloading) strings[Res.string.downloading]
                else if (item.id in state.downloadedCollections) strings[Res.string.downloaded]
                else strings[Res.string.download],
                "download",
                quick = true,
                enabled = tracks.isNotEmpty() && item.id !in state.downloading,
            ) {
                model.accept(BellIntent.Download(item, tracks))
            }
        )
        add(
            MusicMenuAction(
                if (item.userData.favorite) strings[Res.string.unfavorite]
                else strings[Res.string.favorites],
                if (item.userData.favorite) "star.fill" else "star",
                quick = true,
                enabled = real,
            ) {
                model.accept(BellIntent.Favorite(item))
            }
        )
        add(
            MusicMenuAction(strings[Res.string.share], "share", quick = true, enabled = real) {
                share("${state.session?.server}/web/#/details?id=${item.id}")
            }
        )
        add(
            MusicMenuAction(
                if (state.pins.any { it.id == item.id }) strings[Res.string.unpin]
                else if (playlist) strings[Res.string.pin_playlist]
                else strings[Res.string.pin_album],
                "pin",
                enabled = real && item.type in listOf("Playlist", "MusicAlbum"),
            ) {
                model.accept(BellIntent.Pin(item))
            }
        )
        if (playlist) {
            add(
                MusicMenuAction(
                    strings[Res.string.edit_playlist],
                    "edit",
                    enabled =
                        state.playlistInfo?.canEdit == true ||
                            state.playlistInfo?.owner == state.session?.user?.id,
                ) {
                    model.accept(BellIntent.Dialog(LibraryDialog.EditPlaylist(item)))
                }
            )
            add(
                MusicMenuAction(
                    strings[Res.string.add_music],
                    "plus",
                    enabled =
                        state.playlistInfo?.canEdit == true ||
                            state.playlistInfo?.owner == state.session?.user?.id,
                ) {
                    model.accept(BellIntent.Dialog(LibraryDialog.AddMusic(item)))
                }
            )
        }
        add(
            MusicMenuAction(
                strings[Res.string.add_to_playlist],
                "playlist",
                enabled = tracks.isNotEmpty(),
            ) {
                model.accept(BellIntent.Dialog(LibraryDialog.AddToPlaylist(tracks)))
            }
        )
        add(
            MusicMenuAction(
                strings[Res.string.suggest_less],
                "dislike",
                separatorBefore = true,
                enabled = real,
            ) {
                model.accept(BellIntent.SuggestLess(item))
            }
        )
        if (item.type == "MusicAlbum" && item.id in state.downloadedCollections)
            add(
                MusicMenuAction(
                    strings[Res.string.remove_download],
                    "xmark.circle",
                    separatorBefore = true,
                    enabled =
                        item.id !in state.downloading && tracks.none { it.id in state.downloading },
                ) {
                    model.accept(BellIntent.RemoveDownload(item))
                }
            )
        add(
            MusicMenuAction(
                strings[Res.string.delete_from_library],
                "trash",
                separatorBefore =
                    !(item.type == "MusicAlbum" && item.id in state.downloadedCollections),
                destructive = true,
                enabled = real && model.canDelete(item),
            ) {
                model.accept(BellIntent.Dialog(LibraryDialog.Delete(item)))
            }
        )
    }
}

@Composable
fun MusicLibraryDialog(model: BellStore, state: BellState) {
    val text_add_music = stringResource(Res.string.add_music)
    val text_add_to_playlist = stringResource(Res.string.add_to_playlist)
    val text_cancel = stringResource(Res.string.cancel)
    val text_create = stringResource(Res.string.create)
    val text_delete = stringResource(Res.string.delete)
    val text_edit_playlist = stringResource(Res.string.edit_playlist)
    val text_add_song_count = stringResource(Res.string.add_song_count)
    val text_delete_playlist_warning = stringResource(Res.string.delete_playlist_warning)
    val text_delete_media_warning = stringResource(Res.string.delete_media_warning)
    val text_delete_item_confirmation = stringResource(Res.string.delete_item_confirmation)
    val text_name = stringResource(Res.string.name)
    val text_new_playlist = stringResource(Res.string.new_playlist)
    val text_save = stringResource(Res.string.save)
    val text_search_library = stringResource(Res.string.search_library)

    val dismiss = { model.accept(BellIntent.Dialog(null)) }
    val activeDelete = state.dialog as? LibraryDialog.Delete
    var retainedDelete by remember { mutableStateOf<Item?>(null) }
    SideEffect { if (activeDelete != null) retainedDelete = activeDelete.item }
    val deleteItem = activeDelete?.item ?: retainedDelete
    if (deleteItem != null)
        MusicDeleteAlert(
            visible = activeDelete != null,
            title = formatBellString(text_delete_item_confirmation, deleteItem.name),
            message =
                if (deleteItem.type == "Playlist") text_delete_playlist_warning
                else text_delete_media_warning,
            cancelLabel = text_cancel,
            deleteLabel = text_delete,
            enabled = !state.loading,
            dismiss = dismiss,
            confirm = {
                if (activeDelete != null && !state.loading)
                    model.accept(BellIntent.Delete(activeDelete.item))
            },
        )
    val dialog = state.dialog ?: return
    when (dialog) {
        LibraryDialog.AddToQueue -> {
            val title = stringResource(Res.string.add_songs_to_queue)
            val search = stringResource(Res.string.search_library)
            val add = stringResource(Res.string.add_song_count)
            var query by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf(setOf<String>()) }
            val songs =
                state.songs.filter {
                    query.isBlank() ||
                        it.name.contains(query, true) ||
                        it.artist.contains(query, true)
                }
            MusicActionSheet(title, dismiss, expanded = true) {
                BellField(query, { query = it }, search)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(songs, key = { it.id }) { song ->
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clickable {
                                selected =
                                    if (song.id in selected) selected - song.id
                                    else selected + song.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicArtwork(model, state, song, Modifier.size(44.dp), 5.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                MusicText(song.name, 17)
                                MusicText(song.artist, 13, Color.Gray)
                            }
                            MusicIcon(
                                if (song.id in selected) "check" else "plus",
                                MusicAccent,
                                20.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                BellButton(
                    formatBellString(add, selected.size),
                    {
                        model.accept(
                            BellIntent.EnqueueMany(state.songs.filter { it.id in selected })
                        )
                        dismiss()
                    },
                    Modifier.fillMaxWidth(),
                    selected.isNotEmpty(),
                )
            }
        }
        is LibraryDialog.AddToPlaylist ->
            MusicActionSheet(text_add_to_playlist, dismiss, expanded = true) {
                BellButton(
                    text_new_playlist,
                    { model.accept(BellIntent.Dialog(LibraryDialog.CreatePlaylist(dialog.items))) },
                    Modifier.fillMaxWidth(),
                    !state.loading,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(state.playlists, key = { it.id }) { list ->
                        BellRow(
                            list.name,
                            onClick = {
                                if (!state.loading)
                                    model.accept(BellIntent.AddToPlaylist(list, dialog.items))
                            },
                            leading = {
                                MusicArtwork(model, state, list, Modifier.size(44.dp), 5.dp)
                            },
                        )
                    }
                }
            }
        is LibraryDialog.CreatePlaylist -> {
            var name by remember(dialog) { mutableStateOf("") }
            MusicActionSheet(text_new_playlist, dismiss) {
                BellField(name, { name = it }, text_name)
                Spacer(Modifier.height(20.dp))
                BellButton(
                    text_create,
                    { model.accept(BellIntent.CreatePlaylist(name, dialog.items)) },
                    Modifier.fillMaxWidth(),
                    name.isNotBlank() && !state.loading,
                )
            }
        }
        is LibraryDialog.EditPlaylist -> {
            var name by remember(dialog) { mutableStateOf(dialog.item.name) }
            MusicActionSheet(text_edit_playlist, dismiss) {
                BellField(name, { name = it }, text_name)
                Spacer(Modifier.height(20.dp))
                BellButton(
                    text_save,
                    { model.accept(BellIntent.RenamePlaylist(dialog.item, name)) },
                    Modifier.fillMaxWidth(),
                    name.isNotBlank() && !state.loading,
                )
            }
        }
        is LibraryDialog.AddMusic -> {
            var query by remember(dialog) { mutableStateOf("") }
            var selected by remember(dialog) { mutableStateOf(setOf<String>()) }
            val tracks =
                state.songs.filter {
                    query.isBlank() ||
                        it.name.contains(query, true) ||
                        it.artist.contains(query, true)
                }
            MusicActionSheet(text_add_music, dismiss, expanded = true) {
                BellField(query, { query = it }, text_search_library)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clickable {
                                selected =
                                    if (track.id in selected) selected - track.id
                                    else selected + track.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicArtwork(model, state, track, Modifier.size(44.dp), 5.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                MusicText(track.name, 16)
                                MusicText(track.artist, 13, Color.Gray)
                            }
                            MusicIcon(
                                if (track.id in selected) "check" else "plus",
                                MusicAccent,
                                22.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BellButton(
                    formatBellString(text_add_song_count, selected.size),
                    {
                        model.accept(
                            BellIntent.AddToPlaylist(
                                dialog.playlist,
                                state.songs.filter { it.id in selected },
                            )
                        )
                    },
                    Modifier.fillMaxWidth(),
                    selected.isNotEmpty() && !state.loading,
                )
            }
        }
        is LibraryDialog.Delete -> Unit
    }
}
