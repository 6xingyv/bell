// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The floating row is deliberately excluded from the layer it samples. */
@Composable
internal expect fun QueueGlassHost(
    active: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
)

@Composable
internal expect fun QueueDraggedSurface(
    modifier: Modifier,
    lift: Float,
    content: @Composable () -> Unit,
)

@Composable
internal expect fun QueueConfirmationAlert(
    visible: Boolean,
    message: String,
    cancel: String,
    confirm: String,
    dismiss: () -> Unit,
    action: () -> Unit,
)
