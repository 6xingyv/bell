// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.*

actual fun musicShape(radius: Dp): Shape = RoundedCornerShape(radius)

actual fun artworkPixels(data: ByteArray): IntArray {
    val image = javax.imageio.ImageIO.read(data.inputStream()) ?: return IntArray(0)
    return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
}

@Composable actual fun MusicSystemBars(lightContent: Boolean) {}

@Composable
actual fun rememberDeviceVolume(): DeviceVolume = remember {
    object : DeviceVolume {
        override var current = .5f

        override fun set(value: Float) {
            current = value
        }

        override fun outputSettings() {
            runCatching { Desktop.getDesktop().browse(URI("ms-settings:sound")) }
        }
    }
}

@Composable
actual fun rememberMusicSharer(): (String) -> Unit = remember {
    { value ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
}

@Composable
actual fun MusicBackdrop(
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        background()
        content()
        overlay()
    }
}

@Composable
actual fun MusicGlass(
    modifier: Modifier,
    radius: Dp,
    click: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(musicShape(radius))
            .background(
                if (LocalMusicDark.current) Color.Black.copy(alpha = .25f)
                else Color.White.copy(alpha = .8f)
            )
            .border(.5.dp, Color.White.copy(alpha = .4f), musicShape(radius))
            .then(click?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
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
    Box {
        if (style == MusicMenuStyle.Player)
            Box(
                modifier
                    .size(32.dp)
                    .graphicsLayer { blendMode = BlendMode.Plus }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (anchorActive) Color.White else Color.White.copy(alpha = .12f))
                    .clickable { expanded = true },
                contentAlignment = Alignment.Center,
            ) {
                MusicIcon(
                    anchorIcon,
                    if (anchorActive) Color.Black else Color.White,
                    anchorIconSize,
                )
            }
        else
            MusicGlass(modifier.size(44.dp), 22.dp, { expanded = true }) {
                MusicIcon(
                    anchorIcon,
                    if (LocalMusicDark.current) Color.White else Color.Black,
                    anchorIconSize,
                )
            }
        DropdownMenu(expanded, { expanded = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(a.title)
                            a.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    enabled = a.enabled,
                    onClick = {
                        expanded = false
                        a.action()
                    },
                )
            }
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
    var selected by remember { mutableStateOf<MusicMenuAction?>(null) }
    Box {
        Box(
            modifier.clickable(enabled = actions.isNotEmpty()) {
                selected = null
                expanded = true
            }
        ) {
            content()
        }
        DropdownMenu(expanded, { expanded = false }) {
            (selected?.children ?: actions).forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    enabled = action.enabled,
                    onClick = {
                        if (action.children.isNotEmpty()) selected = action
                        else {
                            expanded = false
                            action.action()
                        }
                    },
                    leadingIcon =
                        if (action.hasAvatar) {
                            {
                                coil3.compose.AsyncImage(
                                    action.avatarUrl,
                                    action.title,
                                    Modifier.size(26.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                        } else null,
                )
            }
        }
    }
}
