// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.MusicTextMenu
import com.mocharealm.bell.ui.utils.MusicLineHeightStyle
import com.mocharealm.bell.ui.utils.drawMusicTextLayers
import com.mocharealm.bell.ui.utils.musicColorGlyphPath
import com.mocharealm.bell.ui.utils.musicColorGlyphRanges
import com.mocharealm.bell.ui.utils.musicPlatformTextStyle
import com.mocharealm.bell.ui.utils.rememberMusicPlusPaint

// Adapted from accompanist lyrics-ui sample: PlayerMarquee.kt / PlayerMetadata.

// Ported from feature-text-engine: lyrics-renderer/src/renderer/draw.rs.
// These are renderer pixels, not density-scaled dp values.
internal const val MUSIC_MARQUEE_GAP_PX = 48f
internal const val MUSIC_MARQUEE_SCROLL_PX_PER_SEC = 40f
internal const val MUSIC_MARQUEE_HOLD_MS = 1600f

internal fun musicMarqueeCycleDistance(
    textWidth: Float,
    viewportWidth: Float,
    leftFadeWidth: Float,
    rightFadeWidth: Float,
): Float = textWidth + viewportWidth + leftFadeWidth + rightFadeWidth + MUSIC_MARQUEE_GAP_PX

internal fun musicMarqueeOffset(currentTimeMillis: Int, cycleDistance: Float): Float {
    val scrollMillis = (cycleDistance / MUSIC_MARQUEE_SCROLL_PX_PER_SEC * 1000f).coerceAtLeast(1f)
    val period = scrollMillis + MUSIC_MARQUEE_HOLD_MS
    val remainder = currentTimeMillis.toFloat() % period
    val time = if (remainder < 0f) remainder + period else remainder
    if (time < MUSIC_MARQUEE_HOLD_MS) return 0f
    val progress = ((time - MUSIC_MARQUEE_HOLD_MS) / scrollMillis).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress) * cycleDistance
}

/** The independent animation clock is sampled during drawing only for overflowing text. */
@Composable
internal fun MusicPlayerMarqueeText(
    value: String,
    style: TextStyle,
    alpha: Float,
    currentTimeMillis: () -> Int,
    modifier: Modifier = Modifier,
    leftFade: Dp = 8.dp,
    rightFade: Dp = 8.dp,
    color: Color = Color.White,
    blendMode: BlendMode = BlendMode.Plus,
    offsetX: () -> Float = { 0f },
) {
    val measurer = rememberTextMeasurer()
    val layout =
        measurer.measure(
            value,
            style,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
    val colorGlyphs =
        remember(value, layout, blendMode) {
            if (blendMode == BlendMode.Plus)
                musicColorGlyphPath(layout, musicColorGlyphRanges(value))
            else null
        }
    val normalPaint = remember { Paint() }
    val density = LocalDensity.current
    val height = with(density) { layout.size.height.toDp() }
    val plusPaint =
        if (blendMode == BlendMode.Plus) rememberMusicPlusPaint()
        else remember(blendMode) { Paint().apply { this.blendMode = blendMode } }
    Canvas(
        modifier.fillMaxWidth().height(height).semantics {
            text = AnnotatedString(value)
        }
    ) {
        if (alpha <= 0f || size.width <= 0f) return@Canvas
        val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        val overflowing = textWidth - size.width > .5f
        val translation = offsetX()
        val fading = overflowing || translation != 0f
        val left = if (fading) leftFade.toPx().coerceAtLeast(0f) else 0f
        val right = if (fading) rightFade.toPx().coerceAtLeast(0f) else 0f
        val top = -layout.size.height * .5f
        val bottom = layout.size.height * 1.5f
        val distance = musicMarqueeCycleDistance(textWidth, size.width, left, right)
        val offset =
            (if (overflowing) musicMarqueeOffset(currentTimeMillis(), distance) else 0f) -
                translation
        val movingGlyphs = colorGlyphs?.let { glyphs ->
            Path().apply {
                addPath(glyphs, Offset(-offset, 0f))
                if (overflowing) addPath(glyphs, Offset(distance - offset, 0f))
            }
        }
        drawMusicTextLayers(
            Rect(-left, top, size.width + right, bottom),
            plusPaint,
            normalPaint,
            movingGlyphs,
            draw = {
                clipRect(-left, top, size.width + right, bottom) {
                    drawText(layout, color = color, topLeft = Offset(-offset, 0f), alpha = alpha)
                    if (overflowing)
                        drawText(
                            layout,
                            color = color,
                            topLeft = Offset(distance - offset, 0f),
                            alpha = alpha,
                        )
                }
            },
            finish = {
                if (left > 0f || right > 0f) {
                    val total = left + size.width + right
                    drawRect(
                        Brush.horizontalGradient(
                            0f to if (left > 0f) Color.Transparent else Color.White,
                            left / total to Color.White,
                            (left + size.width) / total to Color.White,
                            1f to if (right > 0f) Color.Transparent else Color.White,
                            startX = -left,
                            endX = size.width + right,
                        ),
                        topLeft = Offset(-left, top),
                        size = androidx.compose.ui.geometry.Size(total, bottom - top),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
            additive = blendMode == BlendMode.Plus,
        )
    }
}

@Composable
internal fun rememberMetadataAnimationClock(): IntState {
    val time = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var origin = 0L
        while (true) withFrameNanos { now ->
            if (origin == 0L) origin = now
            time.intValue = ((now - origin) / 1_000_000L % Int.MAX_VALUE).toInt()
        }
    }
    return time
}

@Composable
internal fun MusicPlayerMetadata(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    artistActions: List<MusicMenuAction> = emptyList(),
    expanded: Float = 0f,
) {
    val animationTime = rememberMetadataAnimationClock()
    val currentTimeMillis = { animationTime.intValue }
    val base =
        LocalTextStyle.current.copy(
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            letterSpacing = 0.sp,
            textMotion = TextMotion.Animated,
            platformStyle = musicPlatformTextStyle(),
            lineHeightStyle = MusicLineHeightStyle,
        )
    val titleSize = 17f + 3f * expanded.coerceIn(0f, 1f)
    val artistSize = 15f + 5f * expanded.coerceIn(0f, 1f)
    Column(modifier) {
        MusicPlayerMarqueeText(
            title,
            base.copy(
                fontSize = titleSize.sp,
                lineHeight = (titleSize * 1.3f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            1f,
            currentTimeMillis,
        )
        MusicTextMenu(artistActions, Modifier.fillMaxWidth()) {
            MusicPlayerMarqueeText(
                artist,
                base.copy(
                    fontSize = artistSize.sp,
                    lineHeight = (artistSize * 1.3f).sp,
                    fontWeight = FontWeight.Normal,
                ),
                .4f,
                currentTimeMillis,
            )
        }
    }
}
