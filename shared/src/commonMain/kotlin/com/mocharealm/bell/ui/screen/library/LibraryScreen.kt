// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.utils.displayText
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

@Composable
internal fun LibraryScreen(
    model: BellStore,
    state: BellState,
    albums: List<Item>,
    navigate: (String) -> Unit,
    title: String? = null,
    showDownloadedEntry: Boolean = true,
    preserveLatestGridCellSize: Boolean = false,
    editing: LibraryEditing? = null,
) {
    val text_library = stringResource(Res.string.library)
    val text_library_empty_title = stringResource(Res.string.library_empty_title)
    val text_library_empty_description = stringResource(Res.string.library_empty_description)
    val text_recently_added = stringResource(Res.string.recently_added)
    val emptyDownloaded = stringResource(Res.string.downloads_empty_title)
    val emptyDownloadedDescription = stringResource(Res.string.downloads_empty_description)
    val gridColumns = LocalMusicGridColumns.current
    val pinGridColumns = maxOf(gridColumns, 3)

    val viewModel = rememberScreenViewModel(model) { LibraryViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)
    val errorText = screenState.error?.displayText()
    val sections =
        if (showDownloadedEntry) {
            librarySectionOptions(state.librarySections).filter { it.shown }.map { it.section }
        } else {
            LibrarySection.entries.filter { it != LibrarySection.Downloaded }
        }

    MusicPage(title ?: text_library, scrollToTopKey = editing?.mode) {
        if (editing?.mode == LibraryEditMode.Sections) {
            item("edit-sections") { LibrarySectionEditor(editing) }
            return@MusicPage
        }
        if (editing?.mode == LibraryEditMode.Pins) {
            item("edit-pins") {
                LibraryPinEditor(model, state, screenState.pins, pinGridColumns, editing)
            }
        } else if (screenState.pins.isNotEmpty()) {
            items(screenState.pins.chunked(pinGridColumns)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { AlbumTile(model, state, it, Modifier.weight(1f), compact = true) }
                    repeat(pinGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        item {
            Column {
                sections.forEach { section ->
                    LibrarySectionRow(section, Modifier.clickable { navigate(section.route) }) {
                        MusicIcon("chevron", libraryControlColor(), 19.dp)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(28.dp))
            MusicText(text_recently_added, 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
        }
        if (screenState.latest.isEmpty())
            item {
                EmptyMusic(
                    if (showDownloadedEntry) text_library_empty_title else emptyDownloaded,
                    if (showDownloadedEntry) text_library_empty_description
                    else emptyDownloadedDescription,
                )
            }
        items(screenState.latest.chunked(gridColumns)) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { AlbumTile(model, state, it, Modifier.weight(1f)) }
                if (preserveLatestGridCellSize)
                    repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                else if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        errorText?.let { item { MusicText(it, 14, MusicAccent, lines = 3) } }
    }
}

@Composable
private fun libraryControlColor() =
    if (LocalMusicDark.current) Color(0xFF636366) else Color(0xFFC5C5C7)

@Composable
private fun LibrarySectionRow(
    section: LibrarySection,
    modifier: Modifier = Modifier,
    selection: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val separator = if (LocalMusicDark.current) Color(0xFF38383A) else Color(0xFFE4E4E6)
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selection != null) {
            selection()
            Spacer(Modifier.width(19.dp))
        }
        Box(Modifier.width(25.dp), contentAlignment = Alignment.Center) {
            MusicIcon(section.icon, MusicAccent, 25.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicText(stringResource(section.title), 17, modifier = Modifier.weight(1f))
                trailing()
            }
            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                drawRect(separator, size = Size(size.width + 4.dp.toPx(), size.height))
            }
        }
    }
}

@Composable
private fun LibrarySectionEditor(editing: LibraryEditing) {
    val options = editing.sections
    val moveUp = stringResource(Res.string.move_song_up)
    val moveDown = stringResource(Res.string.move_song_down)
    val reorder = stringResource(Res.string.reorder_section)
    val liftColor = if (LocalMusicDark.current) Color(0xFF252527) else Color(0xFFF7F7F8)
    fun move(from: Int, to: Int): Boolean {
        if (from !in options.indices || to !in options.indices || from == to) return false
        editing.sections = options.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }
    ReorderableColumn(list = options, onSettle = { from, to -> move(from, to) }) {
        index,
        option,
        dragging ->
        ReorderableItem(modifier = Modifier.fillMaxWidth()) {
            val toggle = {
                editing.sections =
                    editing.sections.map {
                        if (it.section == option.section) it.copy(shown = !it.shown) else it
                    }
            }
            LibrarySectionRow(
                option.section,
                Modifier.background(if (dragging) liftColor else Color.Transparent),
                selection = {
                    Box(
                        Modifier.size(25.dp)
                            .clickable(role = Role.Checkbox, onClick = toggle)
                            .semantics {
                                toggleableState =
                                    if (option.shown) {
                                        androidx.compose.ui.state.ToggleableState.On
                                    } else androidx.compose.ui.state.ToggleableState.Off
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicIcon(
                            if (option.shown) "checkmark.circle.fill" else "circle",
                            if (option.shown) MusicAccent else libraryControlColor(),
                            23.dp,
                            stringResource(option.section.title),
                        )
                    }
                },
            ) {
                Box(
                    Modifier.width(32.dp).fillMaxHeight().draggableHandle().semantics {
                        contentDescription = reorder
                        customActions = buildList {
                            if (index > 0)
                                add(CustomAccessibilityAction(moveUp) { move(index, index - 1) })
                            if (index < options.lastIndex)
                                add(CustomAccessibilityAction(moveDown) { move(index, index + 1) })
                        }
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    MusicIcon("grabber", libraryControlColor(), 22.dp)
                }
            }
        }
    }
}
