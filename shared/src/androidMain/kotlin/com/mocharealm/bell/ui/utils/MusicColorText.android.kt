// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

internal actual fun musicColorGlyphRanges(value: String): List<IntRange> {
    val iterator = BreakIterator.getCharacterInstance().apply { setText(value) }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var emoji = false
            var emojiPresentation = false
            var variationEmoji = false
            var variationText = false
            var keycap = false
            var offset = start
            while (offset < end) {
                val codepoint = Character.codePointAt(value, offset)
                emoji = emoji || UCharacter.hasBinaryProperty(codepoint, UProperty.EMOJI)
                emojiPresentation =
                    emojiPresentation ||
                        UCharacter.hasBinaryProperty(codepoint, UProperty.EMOJI_PRESENTATION)
                variationEmoji = variationEmoji || codepoint == 0xFE0F
                variationText = variationText || codepoint == 0xFE0E
                keycap = keycap || codepoint == 0x20E3
                offset += Character.charCount(codepoint)
            }
            if (!variationText && (emojiPresentation || (emoji && variationEmoji) || keycap))
                add(start until end)
            start = end
            end = iterator.next()
        }
    }
}
