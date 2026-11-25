// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.composables.MusicMenuAction
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class LibrarySort(val label: StringResource, val usesDates: Boolean = false) {
    Title(Res.string.library_sort_title),
    Added(Res.string.library_sort_date_added, usesDates = true),
    LastPlayed(Res.string.library_sort_last_played, usesDates = true),
    Updated(Res.string.library_sort_updated, usesDates = true),
    PlaylistType(Res.string.library_sort_playlist_type),
    Artist(Res.string.artists),
}

internal class LibraryCollection(
    private val options: List<LibrarySort>,
    initialSort: LibrarySort = LibrarySort.Title,
    private val allowsGrid: Boolean = true,
) {
    var grid by mutableStateOf(allowsGrid)
        private set

    private var sort by mutableStateOf(initialSort)
    private var reversed by mutableStateOf(false)

    @Composable
    fun menu(): List<MusicMenuAction> {
        val actions = mutableListOf<MusicMenuAction>()
        if (allowsGrid) {
            actions +=
                MusicMenuAction(
                    stringResource(Res.string.library_grid),
                    "square.grid.2x2",
                    selected = grid,
                ) {
                    grid = true
                }
            actions +=
                MusicMenuAction(
                    stringResource(Res.string.library_list),
                    "list.bullet",
                    selected = !grid,
                ) {
                    grid = false
                }
        }
        options.forEachIndexed { index, option ->
            val active = sort == option
            val subtitle =
                if (active && option != LibrarySort.PlaylistType) {
                    stringResource(
                        if (option.usesDates) {
                            if (reversed) Res.string.library_sort_oldest
                            else Res.string.library_sort_newest
                        } else {
                            if (reversed) Res.string.library_sort_z_to_a
                            else Res.string.library_sort_a_to_z
                        }
                    )
                } else null
            actions +=
                MusicMenuAction(
                    title = stringResource(option.label),
                    icon = "",
                    selected = active,
                    subtitle = subtitle,
                    separatorBefore = allowsGrid && index == 0,
                ) {
                    if (sort == option && option != LibrarySort.PlaylistType) {
                        reversed = !reversed
                    } else {
                        sort = option
                        reversed = false
                    }
                }
        }
        return actions
    }

    @Composable
    fun sorted(items: List<Item>): List<Item> =
        remember(items, sort, reversed) {
            val title =
                compareBy<Item> {
                    it.sortName?.takeIf(String::isNotBlank)?.lowercase() ?: it.name.lowercase()
                }
            val order =
                when (sort) {
                    LibrarySort.Title -> if (reversed) title.reversed() else title
                    LibrarySort.Added -> dateOrder { it.created }
                    LibrarySort.LastPlayed -> dateOrder { it.userData.lastPlayed }
                    LibrarySort.Updated -> dateOrder { it.lastMediaAdded }
                    LibrarySort.PlaylistType -> compareBy { it.mediaType.orEmpty() }
                    LibrarySort.Artist ->
                        compareBy<Item> { it.artist.lowercase() }
                            .let {
                                if (reversed) it.reversed() else it
                            }
                }
            items.sortedWith(order.then(title))
        }

    private fun dateOrder(value: (Item) -> String?): Comparator<Item> =
        compareBy(nullsLast(if (reversed) naturalOrder<String>() else reverseOrder<String>())) {
            value(it)?.takeUnless { date -> date.startsWith("0001-") }
        }
}

internal fun LazyListScope.libraryCollectionGrid(
    items: List<Item>,
    columns: Int,
    tile: @Composable (Item, Modifier) -> Unit,
) {
    items(items.chunked(columns), key = { it.first().id }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { item -> key(item.id) { tile(item, Modifier.weight(1f)) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
