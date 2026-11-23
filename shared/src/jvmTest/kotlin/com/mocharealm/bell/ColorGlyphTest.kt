// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.ui.utils.musicColorGlyphRanges
import kotlin.test.*

class ColorGlyphTest {
    @Test
    fun preservesEmojiClustersWithoutTreatingTextPresentationAsColor() {
        val value = "Title 👩🏽‍🎤 🇨🇳 1️⃣ ❤️ © ☀︎ Artist"
        val glyphs = musicColorGlyphRanges(value).map { value.substring(it) }
        assertEquals(listOf("👩🏽‍🎤", "🇨🇳", "1️⃣", "❤️"), glyphs)
        assertTrue(musicColorGlyphRanges("普通文字 Latin © ☀︎").isEmpty())
    }
}
