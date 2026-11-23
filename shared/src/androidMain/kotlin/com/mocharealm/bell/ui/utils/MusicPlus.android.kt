// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativePaint

@Composable
internal actual fun rememberMusicPlusPaint(): Paint {
    return remember {
        Paint().apply {
            blendMode = BlendMode.Plus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                applyPerceptualPlusXfermode(nativePaint)
        }
    }
}

internal actual fun Modifier.musicPlus(alpha: Float): Modifier = musicPlus { alpha }

internal actual fun Modifier.musicPlus(alpha: () -> Float): Modifier = composed {
    val paint = rememberMusicPlusPaint()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        drawWithContent {
            paint.alpha = alpha().coerceIn(0f, 1f)
            val contentScope = this
            drawWithMusicPlus(paint) { contentScope.drawContent() }
        }
    } else {
        graphicsLayer {
            this.alpha = alpha()
            blendMode = BlendMode.Plus
        }
    }
}

internal actual fun Modifier.musicKnockout(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        graphicsLayer { blendMode = BlendMode.DstOut }
    else this
