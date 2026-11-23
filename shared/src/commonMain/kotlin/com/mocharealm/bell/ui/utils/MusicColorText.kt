// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult

/** UTF-16 ranges of color emoji, keeping variation selectors, flags and ZWJ sequences together. */
internal expect fun musicColorGlyphRanges(value: String): List<IntRange>

internal fun musicColorGlyphPath(layout: TextLayoutResult, ranges: List<IntRange>): Path? =
    if (ranges.isEmpty()) null
    else
        Path().apply {
            ranges.forEach { addPath(layout.getPathForRange(it.first, it.last + 1)) }
        }

/** Keep the ordinary text additive while color glyphs retain their original RGB values. */
internal fun DrawScope.drawMusicTextLayers(
    bounds: Rect,
    paint: Paint,
    normalPaint: Paint,
    colorGlyphs: Path?,
    draw: () -> Unit,
    finish: () -> Unit = {},
    additive: Boolean = paint.blendMode == BlendMode.Plus,
) {
    val canvas = drawContext.canvas
    fun pass(layerPaint: Paint, clip: ClipOp?) {
        canvas.saveLayer(bounds, layerPaint)
        try {
            if (clip != null && colorGlyphs != null) clipPath(colorGlyphs, clip) { draw() }
            else draw()
            finish()
        } finally {
            canvas.restore()
        }
    }
    if (additive && colorGlyphs != null) {
        pass(paint, ClipOp.Difference)
        pass(normalPaint, ClipOp.Intersect)
    } else pass(paint, null)
}
