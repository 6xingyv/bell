// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.albums

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
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.CoverListRow
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.screen.library.composables.LibraryCollection
import com.mocharealm.bell.ui.screen.library.composables.libraryCollectionGrid
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AlbumsScreen(
    model: BellStore,
    state: BellState,
    albums: List<Item>,
    back: () -> Unit,
    collection: LibraryCollection,
) {
    val text_albums = stringResource(Res.string.albums)
    val sorted = collection.sorted(albums)
    val grid = collection.grid
    val columns = LocalMusicGridColumns.current
    val offline = LocalMusicOffline.current

    DetailList(text_albums, back) {
        if (grid) {
            libraryCollectionGrid(sorted, columns) { item, modifier ->
                AlbumTile(model, state, item, modifier)
            }
        } else {
            items(sorted, key = { it.id }) { item ->
                val link =
                    rememberArtworkLink(item.id) {
                        model.accept(BellIntent.OpenItem(item, offlineOnly = offline))
                    }
                CoverListRow(
                    item.name,
                    item.artist,
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
}
