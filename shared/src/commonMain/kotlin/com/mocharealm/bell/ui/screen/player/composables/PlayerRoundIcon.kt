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
internal fun PlayerRoundIcon(
    icon: String,
    label: String,
    click: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier.size(32.dp)
            .musicPlus()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) .25f else .12f))
            .clickable(onClick = click)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MusicIcon(icon, Color.White, 21.dp)
    }
}
