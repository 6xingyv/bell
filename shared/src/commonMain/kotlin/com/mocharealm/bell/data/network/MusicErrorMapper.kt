// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.network

import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.domain.model.MusicErrorKind
import com.mocharealm.bell.domain.model.MusicException
import io.ktor.client.plugins.*

fun networkMusicError(error: Exception): MusicError =
    when (error) {
        is MusicException -> error.error
        is ResponseException ->
            MusicError(
                if (error.response.status.value in listOf(401, 403)) MusicErrorKind.Unauthorized
                else MusicErrorKind.Request,
                error.response.status.value,
            )
        is HttpRequestTimeoutException -> MusicError(MusicErrorKind.Timeout)
        is IllegalArgumentException -> MusicError(MusicErrorKind.InvalidArgument)
        else -> MusicError(MusicErrorKind.Connection)
    }
