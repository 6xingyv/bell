// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.mesh

internal object AlbumTextureProcessor {
    private const val MAX_RED_SATURATION = 0.6f
    // Leave headroom for the shader's audio-reactive contrast and saturation.
    private const val MAX_RED_LUMINANCE = 0.16

    fun process(bitmap: DesktopMeshBitmap): DesktopMeshBitmap {
        if (bitmap.isRecycled) {
            val fallback =
                DesktopMeshBitmap.createBitmap(32, 32, DesktopMeshBitmap.Config.ARGB_8888)
            fallback.eraseColor(DesktopMeshColor.BLACK)
            return fallback
        }
        val w = 32
        val h = 32
        var img = DesktopMeshBitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        img.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsl = FloatArray(3)
        // Only vivid reds and rose hues need correction. All other colors retain
        // the original AMLL pipeline, including its final integer truncation.
        for (i in pixels.indices) {
            val source = pixels[i]
            val original = processColor(source, saturation = 3f)
            DesktopMeshColorUtils.colorToHSL(source, hsl)
            val strength = redCorrectionStrength(hsl[0], hsl[1])
            if (strength == 0f) {
                pixels[i] = original
                continue
            }

            DesktopMeshColorUtils.colorToHSL(processColor(source, saturation = 1.25f), hsl)
            hsl[1] = hsl[1].coerceAtMost(MAX_RED_SATURATION)
            val softened = DesktopMeshColorUtils.HSLToColor(hsl)
            // Leave brightness headroom for audio response without shifting RGB hue.
            var lowerScale = 0f
            var upperScale = 1f
            var scale = 1f
            if (DesktopMeshColorUtils.calculateLuminance(softened) > MAX_RED_LUMINANCE) {
                repeat(10) {
                    val candidateScale = (lowerScale + upperScale) * 0.5f
                    val candidate =
                        DesktopMeshColor.rgb(
                            (DesktopMeshColor.red(softened) * candidateScale).toInt(),
                            (DesktopMeshColor.green(softened) * candidateScale).toInt(),
                            (DesktopMeshColor.blue(softened) * candidateScale).toInt(),
                        )
                    if (DesktopMeshColorUtils.calculateLuminance(candidate) <= MAX_RED_LUMINANCE) {
                        lowerScale = candidateScale
                    } else {
                        upperScale = candidateScale
                    }
                }
                scale = lowerScale
            }
            pixels[i] =
                DesktopMeshColor.argb(
                    DesktopMeshColor.alpha(source),
                    (DesktopMeshColor.red(original) +
                            (DesktopMeshColor.red(softened) * scale -
                                DesktopMeshColor.red(original)) * strength)
                        .toInt(),
                    (DesktopMeshColor.green(original) +
                            (DesktopMeshColor.green(softened) * scale -
                                DesktopMeshColor.green(original)) * strength)
                        .toInt(),
                    (DesktopMeshColor.blue(original) +
                            (DesktopMeshColor.blue(softened) * scale -
                                DesktopMeshColor.blue(original)) * strength)
                        .toInt(),
                )
        }

        img.recycle()
        img = DesktopMeshBitmap.createBitmap(w, h, DesktopMeshBitmap.Config.ARGB_8888)
        img.setPixels(pixels, 0, w, 0, 0, w, h)

        // 5. stack blur 4x with radius 2
        for (i in 0 until 4) {
            img = stackBlur(img, 2)
        }

        return img
    }

    private fun redCorrectionStrength(hue: Float, saturation: Float): Float {
        val hueWeight =
            when {
                hue <= 10f || hue >= 320f -> 1f
                hue < 25f -> smoothStep((25f - hue) / 15f)
                hue > 300f -> smoothStep((hue - 300f) / 20f)
                else -> 0f
            }
        return hueWeight * smoothStep((saturation - 0.35f) / 0.3f)
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun processColor(color: Int, saturation: Float): Int {
        var r = (DesktopMeshColor.red(color) - 128f) * 0.4f + 128f
        var g = (DesktopMeshColor.green(color) - 128f) * 0.4f + 128f
        var b = (DesktopMeshColor.blue(color) - 128f) * 0.4f + 128f
        val gray = r * 0.3f + g * 0.59f + b * 0.11f
        r = gray * (1f - saturation) + r * saturation
        g = gray * (1f - saturation) + g * saturation
        b = gray * (1f - saturation) + b * saturation
        r = ((r - 128f) * 1.7f + 128f) * 0.75f
        g = ((g - 128f) * 1.7f + 128f) * 0.75f
        b = ((b - 128f) * 1.7f + 128f) * 0.75f
        return DesktopMeshColor.argb(
            DesktopMeshColor.alpha(color),
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            b.toInt().coerceIn(0, 255),
        )
    }

    private fun stackBlur(bitmap: DesktopMeshBitmap, radius: Int): DesktopMeshBitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)

        val r = radius.coerceAtLeast(1)
        val window = r * 2 + 1
        val invSize = 1f / window

        // Horizontal pass — float accumulation, no integer truncation
        val hR = FloatArray(w * h)
        val hG = FloatArray(w * h)
        val hB = FloatArray(w * h)
        val hA = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var rr = 0f
                var gg = 0f
                var bb = 0f
                var aa = 0f
                for (kx in -r..r) {
                    val sx = (x + kx).coerceIn(0, w - 1)
                    val pixel = src[y * w + sx]
                    rr += DesktopMeshColor.red(pixel).toFloat()
                    gg += DesktopMeshColor.green(pixel).toFloat()
                    bb += DesktopMeshColor.blue(pixel).toFloat()
                    aa += DesktopMeshColor.alpha(pixel).toFloat()
                }
                val idx = y * w + x
                hR[idx] = rr * invSize
                hG[idx] = gg * invSize
                hB[idx] = bb * invSize
                hA[idx] = aa * invSize
            }
        }

        // Vertical pass — float accumulation, round-to-nearest
        val dst = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rr = 0f
                var gg = 0f
                var bb = 0f
                var aa = 0f
                for (ky in -r..r) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val idx = sy * w + x
                    rr += hR[idx]
                    gg += hG[idx]
                    bb += hB[idx]
                    aa += hA[idx]
                }
                dst[y * w + x] =
                    DesktopMeshColor.argb(
                        (aa * invSize + 0.5f).toInt().coerceIn(0, 255),
                        (rr * invSize + 0.5f).toInt().coerceIn(0, 255),
                        (gg * invSize + 0.5f).toInt().coerceIn(0, 255),
                        (bb * invSize + 0.5f).toInt().coerceIn(0, 255),
                    )
            }
        }

        val result = DesktopMeshBitmap.createBitmap(w, h, DesktopMeshBitmap.Config.ARGB_8888)
        result.setPixels(dst, 0, w, 0, 0, w, h)
        if (bitmap !== result) bitmap.recycle()
        return result
    }
}
