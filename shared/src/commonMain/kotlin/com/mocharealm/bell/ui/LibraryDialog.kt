// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import com.mocharealm.bell.domain.model.Item

sealed interface LibraryDialog {
    data object AddToQueue : LibraryDialog

    data class AddToPlaylist(val items: List<Item>) : LibraryDialog

    data class CreatePlaylist(val items: List<Item> = emptyList()) : LibraryDialog

    data class EditPlaylist(val item: Item) : LibraryDialog

    data class AddMusic(val playlist: Item) : LibraryDialog

    data class Delete(val item: Item) : LibraryDialog
}
