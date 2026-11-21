// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.runtime.Composable

@Composable
expect fun MusicDeleteAlert(
    visible: Boolean,
    title: String,
    message: String,
    cancelLabel: String,
    deleteLabel: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    confirm: () -> Unit,
)
