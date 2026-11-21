// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.network

internal expect suspend fun readMusicArtwork(
    source: String,
    client: io.ktor.client.HttpClient,
): ByteArray
