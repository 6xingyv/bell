// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.*

internal actual suspend fun readMusicArtwork(source: String, client: HttpClient): ByteArray =
    withContext(Dispatchers.IO) {
        if (source.startsWith("file:"))
            java.io.File(java.net.URI(source.substringBefore('#'))).readBytes()
        else client.get(source).readRawBytes()
    }
