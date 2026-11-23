// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.nav.MusicPlayerGlassLayer
import com.mocharealm.bell.ui.nav.PlayerMotion
import com.mocharealm.bell.ui.nav.PlayerWindowCorners
import com.mocharealm.bell.ui.nav.coverBounds
import com.mocharealm.bell.ui.nav.panelBounds
import com.mocharealm.bell.ui.nav.playerPanelShape
import kotlin.math.roundToInt

internal fun Modifier.recordPlayerSurface(
    layer: GraphicsLayer,
    recordWhenHidden: () -> Boolean = { true },
    drawOriginal: () -> Boolean,
) =
    drawWithContent {
        if (drawOriginal()) drawContent()
        else if (recordWhenHidden()) {
            layer.record { this@drawWithContent.drawContent() }
        }
    }

internal fun Modifier.recordMiniPlayerContent(layer: GraphicsLayer) = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/** The sheet keeps its content aspect ratio while its terminal dock bounds change. */
private fun Modifier.playerRectangle(bounds: () -> Rect, root: () -> Rect) =
    layout { measurable, constraints ->
        val rect = bounds()
        val origin = root().topLeft
        val placeable =
            measurable.measure(
                Constraints.fixed(
                    rect.width.roundToInt().coerceAtLeast(1),
                    rect.height.roundToInt().coerceAtLeast(1),
                )
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((rect.left - origin.x).roundToInt(), (rect.top - origin.y).roundToInt())
        }
    }

private fun liveShape(current: () -> Shape): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        current().createOutline(size, layoutDirection, density)
}

@Composable
internal fun MusicPlayerSurface(
    motion: PlayerMotion,
    miniContent: GraphicsLayer,
    full: GraphicsLayer,
    corners: PlayerWindowCorners,
) {
    if (!motion.inProgress) return
    val progress = motion.progress
    val shape = playerPanelShape(progress, corners)
    val shadowProgress = (progress / .18f).coerceIn(0f, 1f)
    val shadowFade = shadowProgress * shadowProgress * (3f - 2f * shadowProgress)
    val miniPaint = remember { Paint() }
    Box(
        Modifier.playerRectangle(motion::panelBounds, { motion.screenBounds })
            .shadow(16.dp * (1f - progress) * shadowFade, shape, clip = false)
            .clip(shape)
            .drawWithContent {
                val p = motion.progress
                // Live glass samples the navigation content at these current bounds.
                // The opaque player background is above it and fades in with expansion.
                drawContent()
                if (full.size.width > 0 && full.size.height > 0) {
                    val scale = size.width / full.size.width
                    withTransform({ scale(scale, scale, Offset.Zero) }) { drawLayer(full) }
                }
                // Never replay the complete mini-player over the expanding sheet: its
                // glass header and nested content produced the detached white bar/ghosts.
                if (miniContent.size.width > 0 && miniContent.size.height > 0 && p < .18f) {
                    val scale = size.width / miniContent.size.width
                    miniPaint.alpha = 1f - p / .18f
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), miniPaint)
                    try {
                        withTransform({ scale(scale, scale, Offset.Zero) }) {
                            drawLayer(miniContent)
                        }
                    } finally {
                        drawContext.canvas.restore()
                    }
                }
            }
    ) {
        MusicPlayerGlassLayer(Modifier.fillMaxSize(), shape, halfResolution = progress > .6f)
    }
}

@Composable
internal fun MusicPlayingArtworkOverlay(session: Session?, itemId: String?, motion: PlayerMotion) {
    if (!motion.inProgress || motion.thumbnailInMini == null ||
        motion.miniBounds == null || motion.albumArtBounds == null
    ) return
    val image = motion.expandedArtwork?.takeIf { it.session == session && it.itemId == itemId }?.image
        ?: motion.miniArtwork?.takeIf { it.session == session && it.itemId == itemId }?.image
    val context = LocalPlatformContext.current
    val painter = remember(image, context) { image?.asPainter(context) }
    val borderColor = Color.White.copy(alpha = .12f)
    val outline = remember(motion) {
        liveShape {
            val radius = motion.miniRadius + (motion.albumArtRadius - motion.miniRadius) * motion.progress
            musicShape(radius)
        }
    }
    Box(
        Modifier.playerRectangle({ motion.coverBounds() ?: Rect.Zero }, { motion.screenBounds })
            .graphicsLayer {
                val p = motion.progress
                shape = musicShape(motion.miniRadius + (motion.albumArtRadius - motion.miniRadius) * p)
                shadowElevation = 24.dp.toPx() * p
                clip = true
            }
            .background(borderColor)
            .border(.5.dp, borderColor, outline),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Canvas(Modifier.matchParentSize()) {
                with(painter) { draw(size) }
            }
        } else {
            BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                MusicIcon("note", Color.White.copy(alpha = .55f), minOf(maxWidth, maxHeight) * .38f)
            }
        }
    }
}
