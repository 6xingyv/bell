// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.utils.musicPlus

@Composable
internal fun PlayerQueueToggle(
    icon: String,
    label: String,
    active: Boolean,
    modifier: Modifier,
    click: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .musicPlus()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) .45f else .10f))
            .clickable(onClick = click)
            .semantics {
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        MusicIcon(
            icon,
            Color.White.copy(alpha = if (active) 1f else .65f),
            20.dp,
            modifier =
                if (active) Modifier.graphicsLayer { blendMode = BlendMode.DstOut } else Modifier,
        )
    }
}
