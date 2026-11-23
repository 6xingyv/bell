// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
expect fun MusicIcon(
    name: String,
    color: Color = Color.Black,
    size: Dp = 24.dp,
    description: String? = null,
    modifier: Modifier = Modifier,
)

internal fun musicSymbolName(name: String): String =
    when (name) {
        "play" -> "play.fill"
        "pause" -> "pause.fill"
        "forward" -> "forward.fill"
        "backward" -> "backward.fill"
        "back" -> "chevron.backward"
        "chevron" -> "chevron.forward"
        "ellipsis" -> "ellipsis"
        "close" -> "xmark"
        "search" -> "magnifyingglass"
        "plus" -> "plus"
        "check" -> "checkmark"
        "sort" -> "line.3.horizontal.decrease"
        "home" -> "house.fill"
        "grid" -> "square.grid.2x2.fill"
        "star" -> "star"
        "star.fill" -> "star.fill"
        "star.slash" -> "star.slash.fill"
        "note" -> "music.note"
        "songs" -> "music.note"
        "playlist" -> "music.note.list"
        "library" -> "music.note.list"
        "albums" -> "square.stack"
        "artist" -> "person.fill"
        "microphone" -> "music.microphone"
        "downloadOutline" -> "arrow.down.circle"
        "download" -> "arrow.down.circle.fill"
        "arrowDown" -> "arrow.down"
        "queue" -> "list.bullet"
        "lyrics" -> "quote.bubble"
        "lyrics.fill" -> "quote.bubble.fill"
        "airplay" -> "airplay.audio"
        "radio" -> "dot.radiowaves.left.and.right"
        "shuffle" -> "shuffle"
        "repeat" -> "repeat"
        "repeatOne" -> "repeat.1"
        "autoplay" -> "infinity"
        "grabber" -> "line.3.horizontal"
        "speaker" -> "speaker.fill"
        "volume" -> "speaker.wave.3.fill"
        "share" -> "square.and.arrow.up"
        "pin" -> "pin"
        "trash" -> "trash"
        "edit" -> "pencil"
        "dislike" -> "hand.thumbsdown"
        "info" -> "info.circle"
        "translation" -> "translate"
        "pronunciation" -> "character.bubble"
        "pronunciation.off" -> "character.bubble"
        "shareLyrics" -> "quote.bubble"
        else -> name
    }

// Exact glyph values from Glasmio's generated SF Symbols catalog for the desktop host.
internal fun musicSymbolCodePoint(name: String): Int? =
    when (musicSymbolName(name)) {
        "music.microphone" -> 0x10046B
        "quote.bubble.fill" -> 0x10032F
        "arrow.down.circle" -> 0x100078
        "infinity" -> 0x100BE0
        "repeat.1" -> 0x10029F
        "line.3.horizontal" -> 0x100307
        "circle.lefthalf.filled" -> 0x100002
        "circle.righthalf.filled" -> 0x100003
        "airplay.audio" -> 0x100462
        "arrow.down" -> 0x100129
        "arrow.down.circle.fill" -> 0x100079
        "backward.fill" -> 0x10028A
        "character.bubble" -> 0x100330
        "checkmark" -> 0x100185
        "checkmark.circle.fill" -> 0x100063
        "checklist" -> 0x100DFE
        "circle" -> 0x100000
        "chevron.backward" -> 0x100BF6
        "chevron.forward" -> 0x100BFB
        "dot.radiowaves.left.and.right" -> 0x100319
        "ellipsis" -> 0x100360
        "forward.fill" -> 0x10028C
        "hand.thumbsdown" -> 0x100281
        "house.fill" -> 0x10039F
        "info.circle" -> 0x100174
        "line.3.horizontal.decrease" -> 0x100713
        "list.bullet" -> 0x1002F2
        "magnifyingglass" -> 0x1002AB
        "mic" -> 0x1002B0
        "minus" -> 0x10017D
        "music.note" -> 0x10046A
        "music.note.list" -> 0x10046C
        "pause.fill" -> 0x100286
        "pencil" -> 0x10020A
        "person.fill" -> 0x10026A
        "pin" -> 0x1003A6
        "play.fill" -> 0x100284
        "plus" -> 0x10017C
        "quote.bubble" -> 0x10032E
        "repeat" -> 0x10029E
        "shuffle" -> 0x10029D
        "speaker.fill" -> 0x1002A1
        "speaker.wave.3.fill" -> 0x1002A9
        "square.and.arrow.up" -> 0x100202
        "square.grid.2x2.fill" -> 0x100688
        "square.grid.2x2" -> 0x1001F7
        "square.stack" -> 0x10040B
        "star" -> 0x1002C2
        "star.fill" -> 0x1002C3
        "star.slash.fill" -> 0x1002C8
        "translate" -> 0x100B99
        "trash" -> 0x100211
        "text.line.last.and.arrowtriangle.forward" -> 0x101640
        "text.line.first.and.arrowtriangle.forward" -> 0x10163F
        "stop.fill" -> 0x1006F7
        "xmark.circle" -> 0x100060
        "xmark" -> 0x100184
        else -> null
    }
