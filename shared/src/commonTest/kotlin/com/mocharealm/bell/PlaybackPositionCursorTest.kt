// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.ui.utils.PlaybackPositionCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPositionCursorTest {
    @Test
    fun statusCorrectionsDoNotFreezeTheDisplayClock() {
        val cursor = PlaybackPositionCursor()
        var sample = AudioSnapshot(1000, 10000, false, sampledAt = 0, revision = 1)
        var previous = cursor.update(sample, 0)
        for (now in 8L..800L step 8) {
            if (now % 104L == 0L)
                sample = sample.copy(positionMs = 1000 + now - 35, sampledAt = now)
            val position = cursor.update(sample, now)
            assertTrue(position - previous in 7..9, "Clock stepped ${position-previous} ms at $now")
            previous = position
        }
        assertTrue(kotlin.math.abs(previous - 1765) < 35)
    }

    @Test
    fun explicitSeekAndPauseAreImmediate() {
        val cursor = PlaybackPositionCursor()
        cursor.update(AudioSnapshot(9000, 10000, false, sampledAt = 0, revision = 1), 0)
        assertEquals(
            1200,
            cursor.update(AudioSnapshot(1200, 10000, false, sampledAt = 100, revision = 2), 100),
        )
        assertEquals(
            1205,
            cursor.update(AudioSnapshot(1205, 10000, true, sampledAt = 110, revision = 2), 200),
        )
    }

    @Test
    fun staleAndBufferingSamplesCannotRunLyricsAhead() {
        val cursor = PlaybackPositionCursor()
        val sample = AudioSnapshot(1000, 10000, false, sampledAt = 0, revision = 1)
        for (now in 0L..5000L step 16) assertTrue(cursor.update(sample, now) <= 1150)
        assertEquals(1000, cursor.update(sample.copy(buffering = true, sampledAt = 5000), 5000))
        assertEquals(1200, cursor.update(sample.copy(positionMs = 1200, sampledAt = 5100), 5100))
    }
}
