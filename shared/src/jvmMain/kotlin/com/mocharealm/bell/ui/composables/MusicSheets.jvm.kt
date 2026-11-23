// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable
actual fun MusicMenuSource(content: @Composable () -> Unit) {
    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun MusicActionSheet(
    title: String,
    dismiss: () -> Unit,
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun MusicListSheet(
    title: String,
    visible: Boolean,
    dismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    if (visible)
        ModalBottomSheet(onDismissRequest = dismiss) {
            Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                content = content,
            )
        }
}
