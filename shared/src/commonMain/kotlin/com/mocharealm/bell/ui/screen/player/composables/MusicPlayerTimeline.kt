// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.musicShape
import com.mocharealm.bell.ui.composables.musicTime
import com.mocharealm.bell.ui.utils.musicPlus
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MusicPlayerTimeline(
    position: IntState,
    duration: Long,
    buffering: Boolean,
    seek: Float?,
    change: (Float) -> Unit,
    finished: () -> Unit,
) {
    val text_buffering = stringResource(Res.string.buffering)
    val text_original_audio = stringResource(Res.string.original_audio)

    val current = position.intValue
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        MusicProgress(
            seek ?: (current.toFloat() / duration).coerceIn(0f, 1f),
            change,
            finished,
            indeterminate = buffering && seek == null,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicText(
                musicTime(current.toLong()),
                12,
                Color.White.copy(alpha = .45f),
                FontWeight.Medium,
                Modifier.weight(1f),
                tabularNumbers = true,
            )
            Box(
                Modifier.musicPlus()
                    .clip(musicShape(5.dp))
                    .background(Color.White.copy(alpha = .1f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                MusicText(
                    if (buffering) text_buffering else text_original_audio,
                    11,
                    Color.White.copy(alpha = .5f),
                    FontWeight.Medium,
                )
            }
            MusicText(
                "−${musicTime(duration-current)}",
                12,
                Color.White.copy(alpha = .45f),
                FontWeight.Medium,
                Modifier.weight(1f),
                align = androidx.compose.ui.text.style.TextAlign.End,
                tabularNumbers = true,
            )
        }
    }
}
