// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.ui.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun PlaylistEditorSheet(
    title: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    submit: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    RequireMusicMenuRecording()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = defaultGlassColors(dark, MusicAccent)
    val sheet = rememberGlasmioSheetState()
    val close = stringResource(Res.string.close)
    val done = stringResource(Res.string.create)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalMusicDark provides dark,
        LocalMusicPlayerPlus provides false,
    ) {
        GlasmioBottomSheet(
            onDismissRequest = dismiss,
            title = title,
            sheetState = sheet,
            backdrop = LocalBlurBackdrop.current,
            contentWindowInsets = { WindowInsets.statusBars },
            topBar = {
                GlasmioSheetTopToolbar(
                    title,
                    navigation = {
                        GlasmioSheetToolbarButton(onClick = dismiss) {
                            MusicIcon("xmark", colors.content, 23.dp, close)
                        }
                    },
                    actions = {
                        GlasmioIconButton(
                            onClick = submit,
                            enabled = enabled,
                            style = GlassSurfaceStyle.Navigation,
                            navigationSurfaceColor = MusicAccent,
                        ) {
                            MusicIcon("checkmark", Color.White, 23.dp, done)
                        }
                    },
                )
            },
        ) {
            val padding = LocalGlasmioSheetContentPadding.current
            val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom = if (keyboard) 0.dp else padding.calculateBottomPadding(),
                    )
            ) {
                content(sheet.currentValue == GlasmioSheetValue.Large)
            }
        }
    }
}

@Composable
internal actual fun rememberPlaylistCoverPicker(
    onPicked: (ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null)
                scope.launch {
                    val jpeg =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bitmap =
                                    ImageDecoder.decodeBitmap(
                                        ImageDecoder.createSource(resolver, uri)
                                    ) { decoder, info, _ ->
                                        val size = info.size
                                        val scale =
                                            (1280f / maxOf(size.width, size.height)).coerceAtMost(
                                                1f
                                            )
                                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                        decoder.setTargetSize(
                                            (size.width * scale).toInt().coerceAtLeast(1),
                                            (size.height * scale).toInt().coerceAtLeast(1),
                                        )
                                    }
                                try {
                                    ByteArrayOutputStream().use { bytes ->
                                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes))
                                            null
                                        else bytes.toByteArray()
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                                .getOrNull()
                        }
                    if (jpeg == null) failed() else picked(jpeg)
                }
        }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
