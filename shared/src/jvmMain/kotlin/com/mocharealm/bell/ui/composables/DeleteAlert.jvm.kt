// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
actual fun MusicDeleteAlert(
    visible: Boolean,
    title: String,
    message: String,
    cancelLabel: String,
    deleteLabel: String,
    enabled: Boolean,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(dismiss) { Text(cancelLabel) } },
        confirmButton = {
            TextButton(confirm, enabled = enabled) { Text(deleteLabel, color = MusicAccent) }
        },
    )
}
