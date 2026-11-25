// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.LocalMusicMenuRecording
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicText
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.glass.render.backdrops.*
import io.github.neoruaa.glasmio.ui.*

@Composable
internal actual fun QueueGlassHost(
    active: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    // Keep content in the same composition slot when lifting/releasing a row:
    // moving the list into a different host would cancel its pointer gesture.
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (active) Modifier.layerBackdrop(backdrop, reuseContent = true)
                        else Modifier
                    )
                    .trackBackdropPosition(backdrop)
            ) {
                content()
            }
            overlay()
        }
    }
}

@Composable
internal actual fun QueueDraggedSurface(
    modifier: Modifier,
    lift: Float,
    content: @Composable () -> Unit,
) {
    val shape = ContinuousRoundedRectangle(10.dp)
    CompositionLocalProvider(LocalGlassColors provides defaultGlassColors(true, MusicAccent)) {
        GlassSurface(
            modifier.shadow(14.dp * lift, shape, clip = false),
            shape = shape,
            refractionHeight = 0.dp,
            refractionAmount = 0.dp,
            frostRadius = 18.dp * lift,
            navigationSurfaceAlphaMultiplier = .45f * lift,
            preserveBackdropColors = true,
        ) {
            content()
        }
    }
}

@Composable
internal actual fun QueueConfirmationAlert(
    visible: Boolean,
    message: String,
    cancel: String,
    confirm: String,
    dismiss: () -> Unit,
    action: () -> Unit,
) {
    val owner = LocalMusicMenuRecording.current
    val dark = LocalMusicDark.current
    CompositionLocalProvider(LocalGlassColors provides defaultGlassColors(dark, MusicAccent)) {
        GlasmioAlertDialog(
            visible = visible,
            onDismissRequest = dismiss,
            title = "",
            titleContent = {
                MusicText(
                    message,
                    17,
                    if (dark) androidx.compose.ui.graphics.Color.White
                    else androidx.compose.ui.graphics.Color.Black,
                    lines = 4,
                )
            },
            backdrop = LocalBlurBackdrop.current,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(cancel, dismiss, GlasmioAlertButtonRole.Cancel),
                    GlasmioAlertButtonSpec(confirm, action, GlasmioAlertButtonRole.Destructive),
                ),
        ) {
            DisposableEffect(owner) {
                owner?.let { it.consumers++ }
                onDispose { owner?.let { it.consumers-- } }
            }
        }
    }
}
