// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun PlaylistEditorSheet(
    title: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    submit: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, dragHandle = null) {
        val foreground = if (LocalMusicDark.current) Color.White else Color.Black
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)) {
            IconButton(dismiss, Modifier.align(Alignment.CenterStart)) {
                MusicIcon("xmark", foreground, 22.dp, stringResource(Res.string.close))
            }
            MusicText(title, 17, modifier = Modifier.align(Alignment.Center))
            IconButton(submit, Modifier.align(Alignment.CenterEnd), enabled = enabled) {
                MusicIcon(
                    "checkmark",
                    if (enabled) MusicAccent else Color.Gray,
                    22.dp,
                    stringResource(Res.string.create),
                )
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) { content(true) }
    }
}

@Composable
internal actual fun rememberPlaylistCoverPicker(
    onPicked: (ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val label = stringResource(Res.string.choose_playlist_cover)
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    return {
        EventQueue.invokeLater {
            val chooser =
                JFileChooser().apply {
                    dialogTitle = label
                    fileFilter = FileNameExtensionFilter(label, *ImageIO.getReaderFileSuffixes())
                }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                scope.launch {
                    val jpeg =
                        withContext(Dispatchers.IO) {
                            runCatching { readPlaylistCover(file) }.getOrNull()
                        }
                    if (jpeg == null) failed() else picked(jpeg)
                }
            }
        }
    }
}

private fun readPlaylistCover(file: File): ByteArray? {
    val input = ImageIO.createImageInputStream(file) ?: return null
    input.use {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val sample =
                ((maxOf(reader.getWidth(0), reader.getHeight(0)) - 1) / 1280 + 1).coerceAtLeast(1)
            val image =
                reader.read(
                    0,
                    reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) },
                )
            val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            val graphics = rgb.createGraphics()
            try {
                graphics.color = java.awt.Color.WHITE
                graphics.fillRect(0, 0, rgb.width, rgb.height)
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            return ByteArrayOutputStream().use { bytes ->
                if (ImageIO.write(rgb, "jpeg", bytes)) bytes.toByteArray() else null
            }
        } finally {
            reader.dispose()
        }
    }
}
