// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.Composable
import com.mocharealm.bell.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Resolves parameters after resources are captured outside a LazyListScope/callback. */
fun formatBellString(pattern: String, vararg args: Any?): String =
    Regex("%([0-9]+)" + Regex.escape("$") + "s").replace(pattern) { match ->
        args.getOrNull(match.groupValues[1].toInt() - 1)?.toString().orEmpty()
    }

class BellStrings(private val values: Map<StringResource, String>) {
    operator fun get(resource: StringResource): String = values.getValue(resource)
}

@Composable
fun rememberMenuStrings(): BellStrings =
    BellStrings(
        mapOf(
            Res.string.add_music to stringResource(Res.string.add_music),
            Res.string.add_to_playlist to stringResource(Res.string.add_to_playlist),
            Res.string.create_station to stringResource(Res.string.create_station),
            Res.string.delete_from_library to stringResource(Res.string.delete_from_library),
            Res.string.remove_download to stringResource(Res.string.remove_download),
            Res.string.download to stringResource(Res.string.download),
            Res.string.downloaded to stringResource(Res.string.downloaded),
            Res.string.downloading to stringResource(Res.string.downloading),
            Res.string.edit_playlist to stringResource(Res.string.edit_playlist),
            Res.string.favorites to stringResource(Res.string.favorites),
            Res.string.open_artist to stringResource(Res.string.open_artist),
            Res.string.open_album to stringResource(Res.string.open_album),
            Res.string.go_to_album to stringResource(Res.string.go_to_album),
            Res.string.go_to_artist to stringResource(Res.string.go_to_artist),
            Res.string.pin_album to stringResource(Res.string.pin_album),
            Res.string.pin_playlist to stringResource(Res.string.pin_playlist),
            Res.string.pin_song to stringResource(Res.string.pin_song),
            Res.string.share to stringResource(Res.string.share),
            Res.string.share_lyrics to stringResource(Res.string.share_lyrics),
            Res.string.show_credits to stringResource(Res.string.show_credits),
            Res.string.suggest_less to stringResource(Res.string.suggest_less),
            Res.string.unfavorite to stringResource(Res.string.unfavorite),
            Res.string.unpin to stringResource(Res.string.unpin),
        )
    )
