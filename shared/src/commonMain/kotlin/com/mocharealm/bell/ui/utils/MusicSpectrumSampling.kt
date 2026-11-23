// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.playback.MusicSpectrum
import com.mocharealm.bell.domain.playback.MusicSpectrumEnvelope
import com.mocharealm.bell.domain.playback.monotonicMillis
import kotlinx.coroutines.isActive

@androidx.compose.runtime.Composable
internal fun rememberMusicSpectrum(
    player: AudioPlayer,
    active: Boolean,
    resetKey: Any,
): androidx.compose.runtime.State<MusicSpectrum> =
    androidx.compose.runtime.produceState(MusicSpectrum.Zero, player, active, resetKey) {
        value = MusicSpectrum.Zero
        if (active) {
            val envelope = MusicSpectrumEnvelope()
            val power = FloatArray(3)
            var previous = monotonicMillis()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (!player.readSpectrumPower(power)) power.fill(0f)
                }
                val now = monotonicMillis()
                value = envelope.update(power, ((now - previous) / 1000f).coerceIn(.001f, .25f))
                previous = now
                // Sample ahead of the renderer's 30 fps cadence. Scheduling stalls
                // can still delay delivery; native selects by presentation history.
                kotlinx.coroutines.delay(16)
            }
        }
    }
