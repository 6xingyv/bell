// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.mesh

import java.awt.image.BufferedImage

internal class DesktopMeshBitmap(val image: BufferedImage) {
    val width
        get() = image.width

    val height
        get() = image.height

    val isRecycled = false

    fun recycle() {}

    fun eraseColor(color: Int) {
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color)
    }

    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        image.getRGB(x, y, w, h, out, offset, stride)
    }

    fun setPixels(input: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        image.setRGB(x, y, w, h, input, offset, stride)
    }

    enum class Config {
        ARGB_8888
    }

    companion object {
        fun createBitmap(w: Int, h: Int, config: Config) =
            DesktopMeshBitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB))

        fun createScaledBitmap(
            source: DesktopMeshBitmap,
            w: Int,
            h: Int,
            filter: Boolean,
        ): DesktopMeshBitmap {
            val result = createBitmap(w, h, Config.ARGB_8888)
            val g = result.image.createGraphics()
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(source.image, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            return result
        }
    }
}

internal object DesktopMeshColor {
    const val BLACK = 0xff000000.toInt()

    fun alpha(c: Int) = c ushr 24 and 255

    fun red(c: Int) = c ushr 16 and 255

    fun green(c: Int) = c ushr 8 and 255

    fun blue(c: Int) = c and 255

    fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
}

internal object DesktopMeshColorUtils {
    fun colorToHSL(c: Int, out: FloatArray) {
        val r = DesktopMeshColor.red(c) / 255f
        val g = DesktopMeshColor.green(c) / 255f
        val b = DesktopMeshColor.blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val l = (max + min) / 2f
        val h =
            when {
                d == 0f -> 0f
                max == r -> ((g - b) / d) % 6f
                max == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        out[0] = if (h < 0) h + 360 else h
        out[1] = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * l - 1))
        out[2] = l
    }

    fun HSLToColor(hsl: FloatArray): Int {
        val h = hsl[0] / 60f
        val c = (1 - kotlin.math.abs(2 * hsl[2] - 1)) * hsl[1]
        val x = c * (1 - kotlin.math.abs(h % 2 - 1))
        val m = hsl[2] - c / 2
        val rgb =
            when (h.toInt()) {
                0 -> floatArrayOf(c, x, 0f)
                1 -> floatArrayOf(x, c, 0f)
                2 -> floatArrayOf(0f, c, x)
                3 -> floatArrayOf(0f, x, c)
                4 -> floatArrayOf(x, 0f, c)
                else -> floatArrayOf(c, 0f, x)
            }
        return DesktopMeshColor.rgb(
            kotlin.math.round((rgb[0] + m) * 255).toInt(),
            kotlin.math.round((rgb[1] + m) * 255).toInt(),
            kotlin.math.round((rgb[2] + m) * 255).toInt(),
        )
    }

    fun calculateLuminance(c: Int): Double {
        fun linear(x: Int): Double {
            val v = x / 255.0
            return if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
        }
        return .2126 * linear(DesktopMeshColor.red(c)) +
            .7152 * linear(DesktopMeshColor.green(c)) +
            .0722 * linear(DesktopMeshColor.blue(c))
    }
}
