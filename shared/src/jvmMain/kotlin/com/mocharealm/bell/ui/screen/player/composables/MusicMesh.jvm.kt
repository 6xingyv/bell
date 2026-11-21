// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.jetbrains.skia.*

internal class DesktopMeshAlbum(
    val builder: RuntimeShaderBuilder,
    val image: Image,
    val positions: FloatArray,
    val uv: FloatArray,
    val indices: ShortArray,
)
