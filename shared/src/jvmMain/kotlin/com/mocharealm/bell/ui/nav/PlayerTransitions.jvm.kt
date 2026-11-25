// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable internal actual fun playerWindowCorners() = PlayerWindowCorners()

internal actual fun playerPanelShape(progress: Float, corners: PlayerWindowCorners): Shape {
    fun corner(end: Dp) = 24.dp + (end - 24.dp) * progress
    return RoundedCornerShape(
        corner(corners.topLeft),
        corner(corners.topRight),
        corner(corners.bottomRight),
        corner(corners.bottomLeft),
    )
}

@Composable
internal actual fun MusicPlayerGlassLayer(
    modifier: Modifier,
    shape: Shape,
    halfResolution: Boolean,
) {
    Box(modifier.clip(shape).background(Color.White.copy(alpha = .85f)))
}

@OptIn(ExperimentalDeferredTransitionApi::class)
@Composable
actual fun musicSharedArtwork(key: String?): Modifier {
    if (key == null) return Modifier
    val shared = LocalMusicSharedScope.current ?: return Modifier
    val animated = LocalMusicAnimatedScope.current ?: return Modifier
    val config = remember {
        object : SharedTransitionScope.SharedContentConfig {
            override val permitTransformDuringDeferredTransition = true
        }
    }
    return with(shared) {
        Modifier.sharedElement(
            rememberSharedContentState(key, config),
            animated,
            boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 420f) },
            clipInOverlayDuringTransition = MusicArtworkOverlayClip,
            zIndexInOverlay = 2f,
        )
    }
}
