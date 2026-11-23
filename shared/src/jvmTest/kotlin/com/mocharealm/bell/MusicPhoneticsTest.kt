// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.accompanist.lyrics.core.utils.*
import com.mocharealm.accompanist.lyrics.phonetics.*
import com.mocharealm.bell.ui.utils.createMusicPhonetics
import com.mocharealm.bell.ui.utils.isMusicPhoneticCaption
import kotlin.test.*

class MusicPhoneticsTest {
    private val provider = createMusicPhonetics(SharedPackLoader(ClasspathPackLoader())) { it }

    @Test
    fun latinNeverProducesAPlaceholderCaptionOrProjections() {
        listOf(
                "We Don't Talk About Bruno",
                "déjà vu — corazón",
                "Łódź, İstanbul, Việt Nam",
                "’…_123",
            )
            .forEach { text ->
                val result =
                    provider.resolve(
                        PhoneticRequest(
                            text,
                            projectionRanges = listOf(PhoneticTextRange(0, text.length)),
                        )
                    )
                assertNull(result.phonetic, text)
                assertNull(result.projections.single().phonetic, text)
                assertTrue(result.projections.single().aligned)
            }
    }

    @Test
    fun nonLatinCaptionsStillUseTheBundledPacks() {
        assertTrue(provider.getPhonetic("你好", "zh-CN").isMusicPhoneticCaption())
        assertTrue(provider.getPhonetic("東京", "ja").isMusicPhoneticCaption())
    }
}
