// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.musicPlus
import com.mocharealm.bell.ui.utils.musicTextStyle
import org.jetbrains.compose.resources.stringResource

internal fun musicArtistNames(names: List<String>): String =
    when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }

internal val Item.albumArtistDisplay: String
    get() = musicArtistNames(albumArtistNames)

internal fun albumTrackArtist(track: Item, album: Item): String? {
    val songNames =
        track.artists
            .filter { it.isNotBlank() }
            .ifEmpty { track.artistItems.map { it.name }.filter { it.isNotBlank() } }
    if (songNames.isEmpty()) return null
    fun List<String>.identities() = map {
        it.trim().lowercase().replace(Regex("\\s+"), " ")
    }
        .toSet()
    return musicArtistNames(songNames).takeIf {
        songNames.identities() != album.albumArtistNames.identities()
    }
}

internal expect fun musicOverviewText(value: String): String

/** Two-line overview with an inline More label, matching the album reference. */
@Composable
internal fun MusicAlbumOverview(id: String, title: String, overview: String) {
    val text_more = stringResource(Res.string.more)

    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val text = remember(overview) { musicOverviewText(overview) }
    val measurer = rememberTextMeasurer()
    val style =
        MaterialTheme.typography.bodyLarge
            .copy(
                fontSize = 17.sp,
                lineHeight = 21.sp,
                letterSpacing = (-.25).sp,
                color = Color.White.copy(alpha = .75f),
            )
            .musicTextStyle()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val constraints = Constraints(maxWidth = width)
        val layout =
            measurer.measure(AnnotatedString(text), style, constraints = constraints, maxLines = 2)
        val overflow = layout.hasVisualOverflow
        val collapsed =
            remember(text, width, style, density.fontScale) {
                if (!overflow) AnnotatedString(text)
                else {
                    var end =
                        layout.getLineEnd((layout.lineCount - 1).coerceAtMost(1), visibleEnd = true)
                    fun candidate() = buildAnnotatedString {
                        append(text.take(end).trimEnd())
                        append("… ")
                        pushStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold))
                        append(text_more)
                        pop()
                    }
                    while (
                        end > 0 &&
                            measurer
                                .measure(
                                    candidate(),
                                    style,
                                    constraints = constraints,
                                    maxLines = 2,
                                )
                                .hasVisualOverflow
                    ) {
                        end--
                        if (end > 0 && text[end].isLowSurrogate()) end--
                    }
                    candidate()
                }
            }
        Text(
            collapsed,
            modifier =
                Modifier.fillMaxWidth()
                    .musicPlus()
                    .then(if (overflow) Modifier.clickable { expanded = true } else Modifier),
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
    AlbumOverviewSheet(title, overview, expanded) { expanded = false }
}

@Composable
internal expect fun AlbumOverviewSheet(
    title: String,
    overview: String,
    visible: Boolean,
    dismiss: () -> Unit,
)
