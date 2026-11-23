// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import android.graphics.Paint
import android.graphics.RuntimeXfermode
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
internal class PerceptualPlusBlendMode(gain: Float = 2.0f) {
    private val xfermode = RuntimeXfermode(AGSL)

    var gain: Float = gain
        set(value) {
            require(value.isFinite() && value in 0f..8f)
            field = value
            xfermode.setFloatUniform("gain", value)
        }

    init {
        this.gain = gain
    }

    fun applyTo(paint: Paint) {
        paint.xfermode = xfermode
    }

    companion object {
        private val AGSL =
            """
            uniform float gain;

            float decodeChannel(float c) {
                return c <= 0.04045
                    ? c / 12.92
                    : pow((c + 0.055) / 1.055, 2.4);
            }

            float3 toOKLab(float3 rgb) {
                float3 c = float3(
                    decodeChannel(rgb.r),
                    decodeChannel(rgb.g),
                    decodeChannel(rgb.b)
                );

                float3 lms = float3(
                    dot(c, float3(
                        0.4122214708,
                        0.5363325363,
                        0.0514459929
                    )),
                    dot(c, float3(
                        0.2119034982,
                        0.6806995451,
                        0.1073969566
                    )),
                    dot(c, float3(
                        0.0883024619,
                        0.2817188376,
                        0.6299787005
                    ))
                );

                lms = pow(
                    max(lms, float3(0.0)),
                    float3(1.0 / 3.0)
                );

                return float3(
                    dot(lms, float3(
                        0.2104542553,
                        0.7936177850,
                       -0.0040720468
                    )),
                    dot(lms, float3(
                        1.9779984951,
                       -2.4285922050,
                        0.4505937099
                    )),
                    dot(lms, float3(
                        0.0259040371,
                        0.7827717662,
                       -0.8086757660
                    ))
                );
            }

            half4 main(half4 src, half4 dst) {
                float4 s = float4(src);
                float4 d = float4(dst);

                float4 base = min(
                    s + d,
                    float4(1.0)
                );

                if (s.a <= 0.0 || d.a <= 0.0) {
                    return half4(base);
                }

                float3 bg = clamp(
                    d.rgb / max(d.a, 0.000001),
                    0.0, 1.0
                );

                // Probe a fixed amount of white Plus. This is independent of glyph alpha.
                float3 probe = min(bg + float3(0.12), float3(1.0));
                float3 sourceColor = clamp(
                    s.rgb / max(s.a, 0.000001),
                    0.0, 1.0
                );

                float whiteness = smoothstep(
                    0.75, 0.98,
                    min(sourceColor.r, min(sourceColor.g, sourceColor.b))
                );
                float difference = length(toOKLab(probe) - toOKLab(bg));
                float deficiency = 1.0 - smoothstep(0.035, 0.12, difference);

                float brightness = smoothstep(
                    0.65, 0.9,
                    max(bg.r, max(bg.g, bg.b))
                );

                float chroma = max(bg.r, max(bg.g, bg.b))
                             - min(bg.r, min(bg.g, bg.b));
                float saturation = smoothstep(0.35, 0.75, chroma);

                float weight = deficiency
                             * whiteness
                             * brightness
                             * saturation;

                float multiplier = 1.0 + gain * weight;

                return half4(min(
                    d + s * multiplier,
                    float4(1.0)
                ));
            }
            """
                .trimIndent()
    }
}

internal fun applyPerceptualPlusXfermode(paint: Paint) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        Api36PerceptualPlus.mode.applyTo(paint)
}

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private object Api36PerceptualPlus {
    val mode = PerceptualPlusBlendMode()
}
