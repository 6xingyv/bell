// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.navigation3.scene.Scene
import com.mocharealm.bell.ui.composables.MusicMenuAction
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.scaffold.navigation.*
import io.github.neoruaa.glasmio.ui.*

internal val rootPages = setOf(Routes.Home, Routes.Library, Routes.Search)

internal class NativeMusicMenu(val anchor: GlasmioMenuAnchorState) {
    var expanded by mutableStateOf(false)
    var items: List<MusicMenuAction> = emptyList()
    var dark: Boolean = false
}

internal class NativeMusicEntry {
    var animation: AnimatedVisibilityScope? = null
    val list = LazyListState()
    var scroll by mutableStateOf<GlasmioHeaderScrollState?>(null)
    val menus = mutableStateMapOf<String, NativeMusicMenu>()
}

internal val LocalNativeMusicEntry = staticCompositionLocalOf<NativeMusicEntry?> { null }
internal val LocalNativeMusicPadding = staticCompositionLocalOf { PaddingValues() }
internal val LocalNativeMusicBackground = staticCompositionLocalOf<@Composable () -> Unit> { {} }
internal val LocalNativeMusicBottom = staticCompositionLocalOf { 164.dp }

/** Uses the same scaffold, NavDisplay, header clock and scroll adapter as Glasmio's sample. */
internal fun AnimatedContentTransitionScope<Scene<String>>.rootTabChange(
    searchReturn: Boolean = false
): Boolean {
    val from = initialState.entries.lastOrNull()?.contentKey
    val to = targetState.entries.lastOrNull()?.contentKey
    return (from in rootPages && to in rootPages) ||
        to == Routes.Search ||
        (searchReturn && from == Routes.Search)
}
