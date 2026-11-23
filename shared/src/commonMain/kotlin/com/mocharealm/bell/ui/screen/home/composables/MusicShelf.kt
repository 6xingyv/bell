// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.home.composables

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.snapping.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.MusicText

@Composable
internal fun MusicShelf(title: String, model: BellStore, state: BellState, items: List<Item>) {
    Spacer(Modifier.height(16.dp))
    MusicText(title, 24, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(4.dp))
    val list = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth(),
        state = list,
        flingBehavior = rememberSnapFlingBehavior(list, snapPosition = SnapPosition.Start),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            AlbumTile(model, state, item, Modifier.width(156.dp))
        }
    }
}
