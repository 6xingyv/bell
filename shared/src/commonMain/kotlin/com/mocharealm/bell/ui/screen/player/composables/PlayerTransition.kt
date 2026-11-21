// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.nav.LocalPlayerArtworkRole
import com.mocharealm.bell.ui.nav.MusicPlayerGlassLayer
import com.mocharealm.bell.ui.nav.PlayerMotion
import com.mocharealm.bell.ui.nav.PlayerWindowCorners
import com.mocharealm.bell.ui.nav.coverBounds
import com.mocharealm.bell.ui.nav.panelBounds
import com.mocharealm.bell.ui.nav.playerPanelShape
import kotlin.math.roundToInt

internal fun Modifier.recordPlayerSurface(layer: GraphicsLayer, drawOriginal: () -> Boolean) =
    drawWithContent {
        if (drawOriginal()) drawContent()
        else {
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

@Composable
internal fun MusicPlayerSurface(
    motion: PlayerMotion,
    miniContent: GraphicsLayer,
    full: GraphicsLayer,
    corners: PlayerWindowCorners,
) {
    val p = motion.progress
    val visible = p > 0f && p < 1f
    if (!visible) return
    val shape = playerPanelShape(p, corners)
    Box(
        Modifier.playerRectangle(motion::panelBounds, { motion.screenBounds })
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .shadow(16.dp * (1f - p), shape, clip = false)
            .clip(shape)
            .drawWithContent {
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
                    val paint = androidx.compose.ui.graphics.Paint().apply { alpha = 1f - p / .18f }
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
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
        MusicPlayerGlassLayer(Modifier.fillMaxSize(), shape)
    }
}

@Composable
internal fun MusicPlayingArtworkOverlay(model: BellStore, state: BellState, motion: PlayerMotion) {
    if (motion.coverBounds() == null) return
    val p = motion.progress
    val radius = motion.miniRadius + (motion.albumArtRadius - motion.miniRadius) * p
    CompositionLocalProvider(LocalPlayerArtworkRole provides null, LocalMusicDark provides true) {
        MusicArtwork(
            model,
            state,
            state.current,
            Modifier.playerRectangle({ motion.coverBounds() ?: Rect.Zero }, { motion.screenBounds })
                .graphicsLayer { alpha = if (motion.inProgress) 1f else 0f },
            radius,
            shadowElevation = 2.dp + 22.dp * p,
        )
    }
}
