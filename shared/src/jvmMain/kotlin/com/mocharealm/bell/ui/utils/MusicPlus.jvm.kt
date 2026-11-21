// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal actual fun rememberMusicPlusPaint(): Paint = remember {
    Paint().apply { blendMode = BlendMode.Plus }
}

internal actual fun Modifier.musicPlus(alpha: Float): Modifier = graphicsLayer {
    this.alpha = alpha
    blendMode = BlendMode.Plus
}

internal actual fun Modifier.musicKnockout(): Modifier = this
