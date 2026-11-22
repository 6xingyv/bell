// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mocharealm.bell.resources.Res
import com.mocharealm.bell.resources.close
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
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
    if (!visible) return
    Dialog(onDismissRequest = dismiss) {
        CompositionLocalProvider(
            LocalMusicDark provides isSystemInDarkTheme(),
            LocalMusicPlayerPlus provides false,
        ) {
            Surface(shape = musicShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight(.85f)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) { header() }
                            IconButton(onClick = dismiss) {
                                MusicIcon(
                                    "xmark",
                                    MaterialTheme.colorScheme.onSurface,
                                    20.dp,
                                    description = stringResource(Res.string.close),
                                )
                            }
                        }
                        content()
                    }
                    Box(Modifier.matchParentSize(), content = overlay)
                }
            }
        }
    }
}
