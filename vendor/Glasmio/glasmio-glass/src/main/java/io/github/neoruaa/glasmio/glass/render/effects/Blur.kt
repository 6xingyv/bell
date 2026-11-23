/*
 * Copyright 2025 Kyant
 * Copyright 2026 Glasmio contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted from AndroidLiquidGlass 2.0.1 generic capture/effect infrastructure.
 * Modified for Glasmio's owned namespace and Android material renderer.
 */

package io.github.neoruaa.glasmio.glass.render.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import io.github.neoruaa.glasmio.glass.render.BackdropEffectScope
import io.github.neoruaa.glasmio.glass.render.BackdropEffectScopeImpl
import io.github.neoruaa.glasmio.glass.render.isRenderEffectSupported

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (!radius.isFinite() || radius <= 0f) return

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (radius > padding) {
            padding = radius
        }
    }

    val input = renderEffect
    val scope = this as? BackdropEffectScopeImpl
    val cached = scope?.takeIf {
        it.cachedBlurInputKnown &&
            it.cachedBlurInput === input &&
            it.cachedBlurRadiusX == radius &&
            it.cachedBlurRadiusY == radius &&
            it.cachedBlurTileMode == edgeTreatment
    }?.cachedBlurEffect

    renderEffect = cached ?: BlurEffect(input, radius, radius, edgeTreatment).also { effect ->
        scope?.let {
            it.cachedBlurInput = input
            it.cachedBlurInputKnown = true
            it.cachedBlurRadiusX = radius
            it.cachedBlurRadiusY = radius
            it.cachedBlurTileMode = edgeTreatment
            it.cachedBlurEffect = effect
        }
    }
}
