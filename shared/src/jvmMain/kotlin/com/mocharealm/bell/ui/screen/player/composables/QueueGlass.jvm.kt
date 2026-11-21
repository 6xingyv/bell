// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal actual fun QueueGlassHost(
    active: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize()) { content() }
        overlay()
    }
}

@Composable
internal actual fun QueueDraggedSurface(
    modifier: Modifier,
    lift: Float,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(14.dp * lift, RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .14f * lift), RoundedCornerShape(10.dp))
    ) {
        content()
    }
}

@Composable
internal actual fun QueueConfirmationAlert(
    visible: Boolean,
    message: String,
    cancel: String,
    confirm: String,
    dismiss: () -> Unit,
    action: () -> Unit,
) {
    if (visible)
        AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(message) },
            dismissButton = { TextButton(dismiss) { Text(cancel) } },
            confirmButton = { TextButton(action) { Text(confirm) } },
        )
}
