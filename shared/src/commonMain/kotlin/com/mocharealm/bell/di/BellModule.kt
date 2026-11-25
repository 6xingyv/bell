// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.di

import com.mocharealm.bell.data.network.networkMusicError
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import com.mocharealm.bell.domain.repository.NoLocalMediaLibrary
import com.mocharealm.bell.ui.BellStore
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun startBell(
    api: MusicRepository,
    store: SessionStore,
    player: AudioPlayer,
    media: LocalMediaLibrary = NoLocalMediaLibrary,
) = startKoin {
    modules(
        module {
            single<MusicRepository> { api }
            single<SessionStore> { store }
            single<AudioPlayer> { player }
            single<LocalMediaLibrary> { media }
            single {
                BellStore(get(), get(), get(), media = get(), errorMapper = ::networkMusicError)
            }
        }
    )
}
