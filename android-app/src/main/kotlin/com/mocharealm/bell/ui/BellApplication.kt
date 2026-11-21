// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import android.app.*
import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.platform.AndroidAudio
import com.mocharealm.bell.data.platform.AndroidSessionStore
import com.mocharealm.bell.data.platform.androidClient
import com.mocharealm.bell.data.storage.MetadataRefreshJob
import com.mocharealm.bell.data.storage.androidMediaLibrary
import com.mocharealm.bell.di.startBell
import com.mocharealm.bell.ui.service.PlaybackService

class BellApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val client = androidClient()
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
}
