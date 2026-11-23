// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.Composable
import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.domain.model.MusicErrorKind
import com.mocharealm.bell.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicError.displayText(): String =
    when (kind) {
        MusicErrorKind.Unauthorized -> stringResource(Res.string.error_unauthorized)
        MusicErrorKind.Request -> formatBellString(stringResource(Res.string.error_request), status)
        MusicErrorKind.Timeout -> stringResource(Res.string.error_timeout)
        MusicErrorKind.InvalidArgument -> stringResource(Res.string.error_invalid_argument)
        MusicErrorKind.Connection -> stringResource(Res.string.error_connection)
        MusicErrorKind.InvalidServerScheme -> stringResource(Res.string.error_server_scheme)
        MusicErrorKind.InvalidServer -> stringResource(Res.string.error_invalid_server)
        MusicErrorKind.EmptyStation -> stringResource(Res.string.error_empty_station)
        MusicErrorKind.MissingArtist -> stringResource(Res.string.error_missing_artist)
        MusicErrorKind.PlaylistNameRequired -> stringResource(Res.string.error_playlist_name)
        MusicErrorKind.DeleteForbidden -> stringResource(Res.string.error_delete_forbidden)
    }
