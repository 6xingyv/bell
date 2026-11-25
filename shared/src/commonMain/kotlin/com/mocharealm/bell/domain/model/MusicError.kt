// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.model

enum class MusicErrorKind {
    Unauthorized,
    Request,
    Timeout,
    InvalidArgument,
    Connection,
    InvalidServerScheme,
    InvalidServer,
    EmptyStation,
    MissingArtist,
    PlaylistNameRequired,
    DeleteForbidden,
}

data class MusicError(val kind: MusicErrorKind, val status: Int? = null)

class MusicException(val error: MusicError) : IllegalArgumentException(error.kind.name)

fun musicRequire(condition: Boolean, kind: MusicErrorKind) {
    if (!condition) throw MusicException(MusicError(kind))
}

fun defaultMusicError(error: Exception): MusicError =
    (error as? MusicException)?.error ?: MusicError(MusicErrorKind.Connection)
