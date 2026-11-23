// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellStore
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MusicQueueNotice(model: BellStore, modifier: Modifier) {
    val signal by model.nextNotice.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (signal != 0) {
            visible = true
            delay(1600)
            visible = false
        }
    }
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn() + scaleIn(initialScale = .92f),
        exit = fadeOut(),
    ) {
        Row(
            Modifier.clip(musicShape(10.dp))
                .background(Color.White.copy(alpha = .94f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicIcon(
                if (signal > 0) "text.line.first.and.arrowtriangle.forward"
                else "text.line.last.and.arrowtriangle.forward",
                Color.Black,
                17.dp,
            )
            Spacer(Modifier.width(7.dp))
            MusicText(
                stringResource(if (signal > 0) Res.string.play_next else Res.string.play_last),
                13,
                Color.Black,
                FontWeight.SemiBold,
            )
        }
    }
}
