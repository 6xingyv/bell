// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class AlbumState(
    val album: Item?,
    val albumSongs: List<Item>,
    val downloading: Set<String>,
    val downloaded: Set<String>,
)

sealed interface AlbumIntent {
    data class Play(val items: List<Item>, val index: Int = 0, val positionMs: Long = 0) :
        AlbumIntent

    data class Download(val item: Item, val tracks: List<Item> = emptyList()) : AlbumIntent
}

class AlbumViewModel(private val store: BellStore) :
    ScreenViewModel<AlbumState, AlbumIntent>(
        store,
        { s -> AlbumState(s.album, s.albumSongs, s.downloading, s.downloaded) },
    ) {
    override fun accept(intent: AlbumIntent) {
        store.accept(
            when (intent) {
                is AlbumIntent.Play ->
                    BellIntent.Play(intent.items, intent.index, intent.positionMs)
                is AlbumIntent.Download -> BellIntent.Download(intent.item, intent.tracks)
            }
        )
    }
}
