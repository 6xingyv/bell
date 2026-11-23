// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable expect fun BellTheme(content: @Composable () -> Unit)

@Composable
expect fun BellPage(title: String, back: (() -> Unit)? = null, content: LazyListScope.() -> Unit)

@Composable
expect fun BellButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
expect fun BellField(
    value: String,
    change: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    onDone: (() -> Unit)? = null,
)

@Composable
expect fun BellSlider(
    value: Float,
    change: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    finished: () -> Unit,
)

@Composable expect fun BellGroup(content: @Composable ColumnScope.() -> Unit)

@Composable
expect fun BellRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    showTopSeparator: Boolean = false,
    enabled: Boolean = true,
)

@Composable expect fun BellBackHandler(enabled: Boolean, back: () -> Unit)

expect fun artworkShape(): Shape

val LocalBellFullscreen = staticCompositionLocalOf { false }
val MusicAccent = Color(0xFFFF2D55)
val LocalMusicDark = staticCompositionLocalOf { false }

data class MusicMenuAction(
    val title: String,
    val icon: String,
    val quick: Boolean = false,
    val separatorBefore: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val subtitle: String? = null,
    val children: List<MusicMenuAction> = emptyList(),
    val avatarUrl: String? = null,
    val hasAvatar: Boolean = false,
    val action: () -> Unit,
)

enum class MusicMenuStyle {
    Glass,
    Plain,
    Player,
}

interface DeviceVolume {
    val current: Float

    fun set(value: Float)

    fun outputSettings()
}

@Composable expect fun rememberMusicSharer(): (String) -> Unit

@Composable expect fun rememberDeviceVolume(): DeviceVolume

@Composable expect fun MusicSystemBars(lightContent: Boolean)

@Composable
expect fun MusicBackdrop(
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
)

@Composable
expect fun MusicGlass(
    modifier: Modifier = Modifier,
    radius: Dp,
    click: (() -> Unit)? = null,
    onVisualBoundsChanged: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
)

@Composable
expect fun MusicMenu(
    actions: List<MusicMenuAction> = emptyList(),
    modifier: Modifier = Modifier,
    style: MusicMenuStyle = MusicMenuStyle.Glass,
    anchorIcon: String = "ellipsis",
    anchorLabel: String = stringResource(Res.string.more),
    anchorActive: Boolean = false,
    anchorIconSize: Dp = 22.dp,
    expandedActions: (@Composable () -> List<MusicMenuAction>)? = null,
)

@Composable expect fun MusicMenuSource(content: @Composable () -> Unit)

expect fun artworkPixels(data: ByteArray): IntArray

data class MusicHeaderAction(
    val id: String,
    val icon: String,
    val label: String,
    val action: () -> Unit = {},
    val group: String = "primary",
    val menu: List<MusicMenuAction> = emptyList(),
    val avatarUrl: String? = null,
    val avatarInitial: String = "",
)

data class MusicHeader(
    val title: String,
    val root: Boolean = false,
    val dark: Boolean = false,
    val largeTitle: Boolean = true,
    val actions: List<MusicHeaderAction> = emptyList(),
)

val LocalMusicNavigationCompact = staticCompositionLocalOf { 0f }
val LocalMusicNativeSearch = staticCompositionLocalOf { false }

@Composable
expect fun MusicActionSheet(
    title: String,
    dismiss: () -> Unit,
    expanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
)

val LocalMusicSearchFocused = staticCompositionLocalOf { false }
val LocalMusicSearchQuery = staticCompositionLocalOf { "" }
val LocalMusicSearchFocusChange = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
expect fun MusicListSheet(
    title: String,
    visible: Boolean,
    dismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
)

@Composable expect fun BellFormSurface(content: @Composable () -> Unit)

@Composable expect fun MusicSearchFieldGroup(content: @Composable () -> Unit)

@Composable
expect fun MusicTextMenu(
    actions: List<MusicMenuAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

val LocalMusicGroupedPage = staticCompositionLocalOf { false }
