// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.Res
import com.mocharealm.bell.resources.close
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.RequireMusicMenuRecording
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun LyricsShareModal(
    title: String,
    visible: Boolean,
    dismiss: () -> Unit,
    header: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (visible) RequireMusicMenuRecording()
    val dark = isSystemInDarkTheme()
    val colors = defaultGlassColors(dark, MusicAccent)
    val close = stringResource(Res.string.close)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
        LocalMusicDark provides dark,
        LocalMusicPlayerPlus provides false,
    ) {
        GlasmioBottomSheet(
            title = title,
            visible = visible,
            onDismissRequest = dismiss,
            backdrop = LocalBlurBackdrop.current,
            windowOverlay = overlay,
            topBar = {
                GlasmioSheetTopToolbar(
                    title = "",
                    navigation = header,
                    actions = {
                        GlasmioSheetToolbarButton(onClick = dismiss) {
                            MusicIcon("xmark", colors.content, 20.dp, description = close)
                        }
                    },
                )
            },
        ) {
            Column(
                Modifier.fillMaxSize().padding(LocalGlasmioSheetContentPadding.current),
                content = content,
            )
        }
    }
}
