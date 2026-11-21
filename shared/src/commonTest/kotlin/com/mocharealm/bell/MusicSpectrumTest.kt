// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.domain.playback.MusicSpectrum
import com.mocharealm.bell.domain.playback.MusicSpectrumEnvelope
import kotlin.test.*

class MusicSpectrumTest {
    @Test
    fun realPowerRisesDriveAPulseAndSilenceReturnsToZero() {
        val envelope = MusicSpectrumEnvelope()
        repeat(60) { envelope.update(floatArrayOf(.002f, .001f, .0005f), 1f / 60) }
        var peak = 0f
        repeat(12) {
            peak = maxOf(peak, envelope.update(floatArrayOf(.08f, .01f, .003f), 1f / 60).pulse)
        }
        assertTrue(peak > .1f)
        var silent = MusicSpectrum.Zero
        repeat(600) { silent = envelope.update(floatArrayOf(0f, 0f, 0f), .25f) }
        assertEquals(MusicSpectrum.Zero, silent)
    }

    @Test
    fun bandsStayFiniteAndSilentOrInvalidPowerDoesNotCreateFeedback() {
        val envelope = MusicSpectrumEnvelope()
        repeat(20) {
            assertEquals(
                MusicSpectrum.Zero,
                envelope.update(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -1f), 1f / 60),
            )
        }
        repeat(100) {
            val value = envelope.update(floatArrayOf(.1f, .2f, .3f), 1f / 60)
            assertTrue(
                listOf(value.low, value.mid, value.high, value.pulse).all {
                    it.isFinite() && it in 0f..1f
                }
            )
        }
    }
}
