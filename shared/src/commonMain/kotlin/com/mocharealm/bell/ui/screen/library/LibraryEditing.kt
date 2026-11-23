// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

import androidx.compose.runtime.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.nav.Routes
import org.jetbrains.compose.resources.StringResource

internal enum class LibrarySection(
    val route: String,
    val title: StringResource,
    val icon: String,
) {
    Playlists(Routes.Playlists, Res.string.playlists, "playlist"),
    Artists(Routes.Artists, Res.string.artists, "microphone"),
    Albums(Routes.Albums, Res.string.albums, "albums"),
    Songs(Routes.Songs, Res.string.songs, "songs"),
    Downloaded(Routes.Downloaded, Res.string.downloaded, "downloadOutline"),
}

internal data class LibrarySectionOption(val section: LibrarySection, val shown: Boolean = true)

internal fun librarySectionOptions(saved: List<String>): List<LibrarySectionOption> {
    val entries = LibrarySection.entries.associateBy { it.route }
    val configured =
        saved
            .mapNotNull { value ->
                entries[value.removePrefix("-")]?.let {
                    LibrarySectionOption(it, !value.startsWith("-"))
                }
            }
            .distinctBy { it.section }
    return configured +
        LibrarySection.entries
            .filter { section ->
                configured.none { it.section == section }
            }
            .map { LibrarySectionOption(it, shown = it != LibrarySection.Songs) }
}

internal enum class LibraryEditMode {
    Pins,
    Sections,
}

internal class LibraryEditing {
    var mode by mutableStateOf<LibraryEditMode?>(null)
        private set

    var removedPins by mutableStateOf(emptySet<String>())
        private set

    var pinOrder by mutableStateOf(emptyList<String>())
        private set

    var sections by mutableStateOf(emptyList<LibrarySectionOption>())

    fun editPins(order: List<String>) {
        pinOrder = order
        removedPins = emptySet()
        mode = LibraryEditMode.Pins
    }

    fun editSections(saved: List<String>) {
        sections = librarySectionOptions(saved)
        mode = LibraryEditMode.Sections
    }

    fun removePin(id: String) {
        removedPins = removedPins + id
    }

    fun movePin(ids: List<String>, from: Int, to: Int): Boolean {
        if (from !in ids.indices || to !in ids.indices || from == to) return false
        pinOrder = ids.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun finish(model: BellStore) {
        when (mode) {
            LibraryEditMode.Pins -> model.accept(BellIntent.EditPins(pinOrder, removedPins))
            LibraryEditMode.Sections ->
                model.accept(
                    BellIntent.SetLibrarySections(
                        sections.map {
                            if (it.shown) it.section.route else "-" + it.section.route
                        }
                    )
                )
            null -> Unit
        }
        close()
    }

    fun close() {
        mode = null
        removedPins = emptySet()
        pinOrder = emptyList()
        sections = emptyList()
    }
}
