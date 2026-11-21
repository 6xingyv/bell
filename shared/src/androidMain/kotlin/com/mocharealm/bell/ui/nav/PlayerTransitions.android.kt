// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.NativeMusicPlayerBackdrop
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.ui.*

@Composable
internal actual fun MusicPlayerGlassLayer(modifier: Modifier, shape: Shape) {
    val source =
        LocalMusicPlayerBackdropHost.current?.source as? NativeMusicPlayerBackdrop ?: return
    CompositionLocalProvider(
        LocalGlassColors provides defaultGlassColors(source.dark, MusicAccent),
        LocalBottomControlContact provides null,
        LocalIosToolbarActionGroup provides null,
    ) {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            style = GlassSurfaceStyle.Navigation,
            backdrop = source.backdrop,
        ) {}
    }
}

@Composable
internal actual fun playerWindowCorners(): PlayerWindowCorners {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    fun radius(position: Int) =
        with(density) { (view.rootWindowInsets?.getRoundedCorner(position)?.radius ?: 0).toDp() }
    return PlayerWindowCorners(
        radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
        radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
        radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
        radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}

internal actual fun playerPanelShape(progress: Float, corners: PlayerWindowCorners): Shape {
    fun corner(end: Dp) = 24.dp + (end - 24.dp) * progress
    return com.kyant.capsule.AbsoluteContinuousRoundedRectangle(
        corner(corners.topLeft),
        corner(corners.topRight),
        corner(corners.bottomRight),
        corner(corners.bottomLeft),
    )
}

@OptIn(androidx.compose.animation.core.ExperimentalDeferredTransitionApi::class)
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
