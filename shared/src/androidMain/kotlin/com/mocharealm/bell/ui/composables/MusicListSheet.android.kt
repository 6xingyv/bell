// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.*
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MusicListSheet(
    title: String,
    visible: Boolean,
    dismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = defaultGlassColors(dark, MusicAccent)
    val sheet = rememberGlasmioSheetState(skipPartiallyExpanded = false)
    val list = rememberLazyListState()
    val edge = rememberGlasmioHeaderScrollEdge(list)
    val close = stringResource(Res.string.close)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
        LocalMusicPlayerPlus provides false,
        LocalMusicDark provides dark,
    ) {
        GlasmioBottomSheet(
            visible = visible,
            onDismissRequest = dismiss,
            title = title,
            skipPartiallyExpanded = false,
            sheetState = sheet,
            contentWindowInsets = { WindowInsets.statusBars },
            backdrop = LocalBlurBackdrop.current,
            topBarBlurProgress = edge,
            topBar = {
                GlasmioSheetTopToolbar(
                    title,
                    actions = {
                        GlasmioSheetToolbarButton(onClick = dismiss) {
                            MusicIcon("xmark", colors.content, 20.dp, description = close)
                        }
                    },
                )
            },
        ) {
            RequireMusicMenuRecording()
            val padding = LocalGlasmioSheetContentPadding.current
            val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            // The keyboard occludes the sheet; it must not move the sheet's physical bottom
            // corners.
            // Expand once when IME opens, then constrain only the scrolling viewport.
            val keyboardVisible = keyboard > 0.dp
            LaunchedEffect(visible, keyboardVisible) {
                if (visible && keyboardVisible) sheet.expand()
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).imePadding(),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom =
                            if (keyboardVisible) 24.dp
                            else padding.calculateBottomPadding() + 24.dp,
                    ),
                content = content,
            )
        }
    }
}
