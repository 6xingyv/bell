// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.platform.DesktopAudio
import com.mocharealm.bell.data.platform.DesktopSessionStore
import com.mocharealm.bell.data.platform.desktopClient
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.storage.FileMediaLibrary
import com.mocharealm.bell.di.startBell
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellApp
import com.mocharealm.bell.ui.BellStore
import org.jetbrains.compose.resources.stringResource

fun main() {
    val client = desktopClient()
    val root = java.io.File(System.getProperty("user.home"), ".jellify")
    val cache =
        StreamingAudioCache(
            java.io.File(
                System.getProperty("jellify.cacheDir", java.io.File(root, "cache").path),
                "audio-stream",
            ),
            client,
        )
    val media = FileMediaLibrary(java.io.File(root, "media"), cache)
    val koin =
        startBell(JellyfinApi(client), DesktopSessionStore(), DesktopAudio(client, cache), media)
    val model = koin.koin.get<BellStore>()
    application {
        Window(
            onCloseRequest = {
                model.close()
                client.close()
                exitApplication()
            },
            title = stringResource(Res.string.app_name),
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            BellApp(model)
        }
    }
}
