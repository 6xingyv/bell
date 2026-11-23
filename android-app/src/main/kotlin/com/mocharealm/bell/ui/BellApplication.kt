// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import android.app.*
import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.platform.AndroidAudio
import com.mocharealm.bell.data.platform.AndroidSessionStore
import com.mocharealm.bell.data.platform.androidClient
import com.mocharealm.bell.data.storage.MetadataRefreshJob
import com.mocharealm.bell.data.storage.androidMediaLibrary
import com.mocharealm.bell.di.startBell
import com.mocharealm.bell.ui.service.PlaybackService
import io.ktor.client.HttpClient
import okio.Path.Companion.toOkioPath

class BellApplication : Application(), SingletonImageLoader.Factory {
    private lateinit var client: HttpClient

    override fun onCreate() {
        super.onCreate()
        client = androidClient()
        val media = androidMediaLibrary(this, client)
        startBell(
            JellyfinApi(
                client,
                deviceName = { com.mocharealm.bell.data.platform.androidDeviceName(this) },
            ),
            AndroidSessionStore(this),
            AndroidAudio(this, client, media.audioCache) {
                startForegroundService(Intent(this, PlaybackService::class.java))
            },
            media,
        )
        MetadataRefreshJob.schedule(this)
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    KtorNetworkFetcherFactory(
                        httpClient = { client },
                        concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.2).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("bell_image_cache").toOkioPath())
                    .maxSizePercent(0.05)
                    .build()
            }
            .build()
}
