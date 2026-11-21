// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

internal expect fun musicPlatformTextStyle(): PlatformTextStyle

internal val MusicLineHeightStyle =
    LineHeightStyle(
        LineHeightStyle.Alignment.Center,
        LineHeightStyle.Trim.None,
        LineHeightStyle.Mode.Fixed,
    )

internal fun TextStyle.musicTextStyle(family: FontFamily? = fontFamily): TextStyle =
    copy(
        fontFamily = family,
        lineHeight =
            if (fontSize.isSpecified)
                maxOf(if (lineHeight.isSpecified) lineHeight.value else 0f, fontSize.value * 1.4f)
                    .sp
            else lineHeight,
        lineHeightStyle = MusicLineHeightStyle,
        platformStyle = musicPlatformTextStyle(),
    )

internal fun Typography.musicTypography(family: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.musicTextStyle(family),
        displayMedium = displayMedium.musicTextStyle(family),
        displaySmall = displaySmall.musicTextStyle(family),
        headlineLarge = headlineLarge.musicTextStyle(family),
        headlineMedium = headlineMedium.musicTextStyle(family),
        headlineSmall = headlineSmall.musicTextStyle(family),
        titleLarge = titleLarge.musicTextStyle(family),
        titleMedium = titleMedium.musicTextStyle(family),
        titleSmall = titleSmall.musicTextStyle(family),
        bodyLarge = bodyLarge.musicTextStyle(family),
        bodyMedium = bodyMedium.musicTextStyle(family),
        bodySmall = bodySmall.musicTextStyle(family),
        labelLarge = labelLarge.musicTextStyle(family),
        labelMedium = labelMedium.musicTextStyle(family),
        labelSmall = labelSmall.musicTextStyle(family),
    )

internal val LocalMusicPlayerPlus = staticCompositionLocalOf { false }

@androidx.compose.runtime.Composable internal expect fun rememberMusicPlusPaint(): Paint

internal expect fun Modifier.musicPlus(alpha: Float = 1f): Modifier

internal expect fun Modifier.musicKnockout(): Modifier

internal fun DrawScope.drawWithMusicPlus(paint: Paint, draw: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), paint)
    try {
        draw()
    } finally {
        canvas.restore()
    }
}

/** Draw translucent fills additively without placing opaque children in that layer. */
@androidx.compose.runtime.Composable
internal fun Modifier.musicBackground(color: androidx.compose.ui.graphics.Color): Modifier {
    val additive = LocalMusicPlayerPlus.current && color.alpha < 1f
    val paint = if (additive) rememberMusicPlusPaint() else null
    return this.drawBehind {
        if (paint != null) drawWithMusicPlus(paint) { drawRect(color) }
        else drawRect(color, blendMode = BlendMode.SrcOver)
    }
}
