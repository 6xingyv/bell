// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.*
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.playback.monotonicMillis
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

internal class PlaybackPositionCursor {
    private var revision = Long.MIN_VALUE
    private var previousAt = 0L
    private var position = 0.0
    private var paused = true

    fun update(sample: AudioSnapshot, now: Long): Int {
        val halted = sample.paused || sample.buffering || sample.ended
        val target =
            sample.positionMs + (if (halted) 0 else (now - sample.sampledAt).coerceIn(0, 150))
        if (revision != sample.revision || halted || paused) position = target.toDouble()
        else {
            val elapsed = (now - previousAt).coerceAtLeast(0).toDouble()
            val predicted = position + elapsed
            // Ordinary status jitter changes speed slightly instead of freezing or
            // stepping the highlighted syllable at each 100 ms native poll.
            val correction = ((target - predicted) / 500.0).coerceIn(-.1, .1)
            position += elapsed * (1.0 + correction)
        }
        // A stalled native sample must never become an indefinitely running lyric clock.
        position = position.coerceAtMost((sample.positionMs + if (halted) 0 else 150).toDouble())
        revision = sample.revision
        paused = halted
        previousAt = now
        position =
            position.coerceIn(
                0.0,
                (sample.durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE.toLong()).toDouble(),
            )
        return position.toInt()
    }
}

// Same sample/controller split as accompanist lyrics-ui:sample. The lyric draw clock runs per
// frame; native status is sampled less frequently, and explicit seeks reset monotonic smoothing.
@Composable
fun rememberPlaybackPosition(playback: StateFlow<AudioSnapshot>): IntState {
    val position = remember(playback) { mutableIntStateOf(0) }
    LaunchedEffect(playback) {
        val cursor = PlaybackPositionCursor()
        playback.collectLatest { sample ->
            fun update() {
                position.intValue = cursor.update(sample, monotonicMillis())
            }
            update()
            if (!sample.paused) while (true) withFrameNanos { update() }
        }
    }
    return position
}
