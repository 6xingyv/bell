// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

internal actual fun musicOverviewText(value: String): String {
    val kit = javax.swing.text.html.HTMLEditorKit()
    val document = kit.createDefaultDocument()
    kit.read(java.io.StringReader(musicOverviewHtml(value)), document, 0)
    return document.getText(0, document.length).trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun AlbumOverviewSheet(
    title: String,
    overview: String,
    visible: Boolean,
    dismiss: () -> Unit,
) {
    if (visible)
        ModalBottomSheet(onDismissRequest = dismiss) {
            Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            val text = remember(overview) { musicOverviewText(overview) }
            Text(
                text,
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
}
