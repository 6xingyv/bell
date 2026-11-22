// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.nav.MusicPlayerBackdropSource
import com.mocharealm.bell.ui.utils.musicKnockout
import com.mocharealm.bell.ui.utils.musicPlus
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.ui.*
import org.jetbrains.compose.resources.stringResource

internal class NativeMusicPlayerBackdrop(
    val backdrop: io.github.neoruaa.glasmio.glass.render.Backdrop
) : MusicPlayerBackdropSource {
    var dark by mutableStateOf(false)
}

actual fun musicShape(radius: Dp): Shape = ContinuousRoundedRectangle(radius)

actual fun artworkPixels(data: ByteArray): IntArray {
    val image =
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return IntArray(0)
    return try {
        IntArray(image.width * image.height).also {
            image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
    } finally {
        image.recycle()
    }
}

@Composable
actual fun MusicSystemBars(lightContent: Boolean) {
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !lightContent
                isAppearanceLightNavigationBars = !lightContent
            }
        }
    }
}

@Composable
actual fun rememberDeviceVolume(): DeviceVolume {
    val context = LocalContext.current
    return remember(context) {
        object : DeviceVolume {
            val audio = context.getSystemService(AudioManager::class.java)
            override val current
                get() =
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

            override fun set(value: Float) {
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        .toInt()
                        .coerceAtLeast(0),
                    0,
                )
            }

            override fun outputSettings() {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

@Composable
actual fun rememberMusicSharer(): (String) -> Unit {
    val text_share_album = stringResource(Res.string.share_album)

    val context = LocalContext.current
    return remember(context) {
        { value ->
            context.startActivity(
                Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, value),
                        text_share_album,
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
actual fun MusicBackdrop(
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val pageMaterial = LocalGlassBackdrop.current
    GlasmioBackdropHost(
        Modifier.fillMaxSize(),
        sampledContent = {
            background()
            GlasmioBackdropProvider(pageMaterial) { content() }
        },
        overlayContent = { overlay() },
    )
}

@Composable
private fun musicGlassColors(): GlassColors =
    defaultGlassColors(LocalMusicDark.current, MusicAccent)

@Composable
actual fun MusicGlass(
    modifier: Modifier,
    radius: Dp,
    click: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalMusicDark.current
    val colors = musicGlassColors()
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
    ) {
        GlassSurface(
            modifier = modifier,
            shape = ContinuousRoundedRectangle(radius),
            style = GlassSurfaceStyle.Navigation,
            onClick = click,
            content = content,
        )
    }
}

@Composable
actual fun MusicMenu(
    actions: List<MusicMenuAction>,
    modifier: Modifier,
    style: MusicMenuStyle,
    anchorIcon: String,
    anchorLabel: String,
    anchorActive: Boolean,
    anchorIconSize: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    val colors = musicGlassColors()
    val foreground = colors.content
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides foreground,
    ) {
        Box(Modifier.glasmioMenuAnchor(anchor)) {
            if (style == MusicMenuStyle.Glass)
                MusicGlass(
                    modifier.size(44.dp).semantics { contentDescription = anchorLabel },
                    22.dp,
                    { expanded = true },
                ) {
                    MusicIcon(anchorIcon, foreground, anchorIconSize)
                }
            else
                Box(
                    modifier
                        .then(
                            if (style == MusicMenuStyle.Player) Modifier.musicPlus() else Modifier
                        )
                        .size(if (style == MusicMenuStyle.Player) 32.dp else 44.dp)
                        .semantics {
                            contentDescription = anchorLabel
                            selected = anchorActive
                        }
                        .clip(CircleShape)
                        .background(
                            if (style == MusicMenuStyle.Player) {
                                if (anchorActive) Color.White else Color.White.copy(alpha = .12f)
                            } else Color.Transparent
                        )
                        .clickable { expanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    MusicIcon(
                        anchorIcon,
                        if (style == MusicMenuStyle.Player && anchorActive) Color.Black
                        else foreground,
                        anchorIconSize,
                        modifier =
                            if (style == MusicMenuStyle.Player && anchorActive)
                                Modifier.musicKnockout()
                            else Modifier,
                    )
                }
        }
        if (expanded)
            MusicNativeMenu(actions, anchor, LocalBlurBackdrop.current, anchorLabel) {
                expanded = false
            }
    }
}

/** Same menu, row metrics and quick-action header as the Text Editing sample. */
@Composable
internal fun MusicNativeMenu(
    actions: List<MusicMenuAction>,
    anchor: GlasmioMenuAnchorState,
    backdrop: io.github.neoruaa.glasmio.glass.render.Backdrop,
    title: String = stringResource(Res.string.more),
    dismiss: () -> Unit,
) {
    val text_collapsed = stringResource(Res.string.collapsed)
    val text_expanded = stringResource(Res.string.expanded)

    RequireMusicMenuRecording()
    val colors = LocalGlassColors.current
    fun adapt(action: MusicMenuAction): GlasmioCascadingMenuItem =
        GlasmioCascadingMenuItem(
            title = action.title,
            destructive = action.destructive,
            separatorBefore = action.separatorBefore,
            enabled = action.enabled,
            subtitle = action.subtitle,
            onClick = action.action,
            children = action.children.map(::adapt),
            icon = {
                if (action.hasAvatar)
                    Box(
                        Modifier.size(26.dp)
                            .clip(CircleShape)
                            .background(colors.content.copy(alpha = .08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicIcon("music.microphone", colors.secondaryContent, 15.dp)
                        coil3.compose.AsyncImage(
                            action.avatarUrl,
                            action.title,
                            Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                else
                    MusicIcon(
                        action.icon,
                        if (action.destructive) colors.destructive else colors.content,
                        20.dp,
                    )
            },
        )
    val quick = actions.filter { it.quick }
    GlasmioCascadingMenu(
        anchorBounds = anchor.bounds,
        menuAnchorState = anchor,
        items = actions.filterNot { it.quick }.map(::adapt),
        title = title,
        expandedDescription = text_expanded,
        collapsedDescription = text_collapsed,
        onDismiss = dismiss,
        backdrop = backdrop,
        useAccentIcons = false,
        itemHeight = 40.dp,
        separatorHeight = 21.dp,
        headerActions = quick.map(::adapt),
    )
}

internal class MusicMenuRecording {
    var consumers by mutableIntStateOf(0)
}

internal val LocalMusicMenuRecording = staticCompositionLocalOf<MusicMenuRecording?> { null }

@Composable
internal fun RequireMusicMenuRecording() {
    val owner = LocalMusicMenuRecording.current
    DisposableEffect(owner) {
        if (owner != null) owner.consumers++
        onDispose { if (owner != null) owner.consumers-- }
    }
}

@Composable
actual fun MusicMenuSource(content: @Composable () -> Unit) {
    val source = rememberLayerBackdrop()
    val owner = remember { MusicMenuRecording() }
    val popupSource = rememberRasterizedBackdrop(source)
    // No page/player replay while no popup consumes it. With a popup, record once and
    // replay the same live display list, preserving dynamic cross-window sampling.
    val recording =
        if (owner.consumers > 0) Modifier.layerBackdrop(source, reuseContent = true) else Modifier
    CompositionLocalProvider(
        LocalBlurBackdrop provides popupSource,
        LocalMusicMenuRecording provides owner,
    ) {
        Box(Modifier.fillMaxSize().then(recording).trackBackdropPosition(source)) { content() }
    }
}

@Composable
actual fun MusicActionSheet(
    title: String,
    dismiss: () -> Unit,
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    RequireMusicMenuRecording()
    val colors = musicGlassColors()
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
    ) {
        GlasmioBottomSheet(
            onDismissRequest = dismiss,
            title = title,
            fitContent = !expanded,
            backdrop = LocalBlurBackdrop.current,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                MusicText(title, 22, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                content()
            }
        }
    }
}

@Composable
actual fun MusicDeleteAlert(
    visible: Boolean,
    title: String,
    message: String,
    cancelLabel: String,
    deleteLabel: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    val colors = musicGlassColors()
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
    ) {
        GlasmioAlertDialog(
            visible = visible,
            onDismissRequest = dismiss,
            title = title,
            message = message,
            backdrop = LocalBlurBackdrop.current,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(cancelLabel, dismiss, GlasmioAlertButtonRole.Cancel),
                    GlasmioAlertButtonSpec(
                        deleteLabel,
                        confirm,
                        GlasmioAlertButtonRole.Destructive,
                        enabled = enabled,
                    ),
                ),
        ) {
            // The sample retains the dialog while its exit motion runs. Record
            // the backdrop for that lifetime, then release it with the window.
            RequireMusicMenuRecording()
        }
    }
}

@Composable
actual fun MusicTextMenu(
    actions: List<MusicMenuAction>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    val colors = musicGlassColors()
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
    ) {
        Box(
            modifier.glasmioMenuAnchor(anchor).clickable(enabled = actions.isNotEmpty()) {
                expanded = true
            }
        ) {
            content()
        }
        if (expanded)
            MusicNativeMenu(actions, anchor, LocalBlurBackdrop.current) { expanded = false }
    }
}
