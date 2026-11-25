// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.displayText
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NewPlaylistSheet(
    loading: Boolean,
    canUploadCover: Boolean,
    error: MusicError?,
    dismiss: () -> Unit,
    create: (String, ByteArray?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var coverError by remember { mutableStateOf(false) }
    val enabled = name.isNotBlank() && !loading
    val submit = { if (enabled) create(name.trim(), cover) }
    val titleHint = stringResource(Res.string.playlist_title)
    val coverLabel = stringResource(Res.string.choose_playlist_cover)

    PlaylistEditorSheet(
        title = stringResource(Res.string.new_playlist),
        enabled = enabled,
        dismiss = { if (!loading) dismiss() },
        submit = submit,
    ) { presented ->
        val dark = LocalMusicDark.current
        val foreground = if (dark) Color.White else Color.Black
        val coverFill = if (dark) Color(0xFF262628) else Color(0xFFF2F2F3)
        val border = if (dark) Color(0xFF48484B) else Color(0xFFD8D8DA)
        val hint = if (dark) Color(0xFF77777C) else Color(0xFFD4D4D6)
        val pickCover =
            rememberPlaylistCoverPicker(
                onPicked = {
                    cover = it
                    coverError = false
                },
                onError = { coverError = true },
            )
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(presented) {
            if (presented) {
                focus.requestFocus()
                withFrameNanos {}
                keyboard?.show()
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            val shape = musicShape(8.dp)
            Box(
                Modifier.size(180.dp)
                    .clip(shape)
                    .background(coverFill)
                    .border(.5.dp, border, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    AsyncImage(
                        cover,
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.size(64.dp)
                        .shadow(14.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (canUploadCover) MusicAccent else border)
                        .clickable(
                            enabled = canUploadCover && !loading,
                            role = Role.Button,
                            onClick = pickCover,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MusicIcon("photo", Color.White, 26.dp, coverLabel)
                }
            }
            Spacer(Modifier.height(60.dp))
            val textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = textStyle,
                singleLine = true,
                readOnly = loading,
                cursorBrush = SolidColor(MusicAccent),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                decorationBox = { input ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (name.isEmpty())
                            MusicText(
                                titleHint,
                                20,
                                hint,
                                FontWeight.SemiBold,
                                align = TextAlign.Center,
                            )
                        input()
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.fillMaxWidth().height(.5.dp).background(border))
            val errorText =
                if (coverError) stringResource(Res.string.error_playlist_cover)
                else error?.displayText()
            if (errorText != null) {
                Spacer(Modifier.height(10.dp))
                MusicText(errorText, 14, MusicAccent, lines = 3, align = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal expect fun PlaylistEditorSheet(
    title: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    submit: () -> Unit,
    content: @Composable (Boolean) -> Unit,
)

@Composable
internal expect fun rememberPlaylistCoverPicker(
    onPicked: (ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit
