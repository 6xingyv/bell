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
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.ListeningEntry
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.ListeningAvatar
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicText

@Composable
internal fun PlayerQueueRow(
    model: BellStore,
    state: BellState,
    track: Item,
    modifier: Modifier = Modifier,
    handle: Modifier? = null,
    entry: ListeningEntry? = null,
    play: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clickable(onClick = play),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicArtwork(model, state, track, Modifier.size(48.dp), 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MusicText(track.name, 17, Color.White.copy(alpha = .85f))
            MusicText(track.artist, 13, Color.White.copy(alpha = .45f))
        }
        if (handle != null && state.listening.active && entry?.userName != null) {
            Spacer(Modifier.width(8.dp))
            ListeningAvatar(model, state, entry.userId, entry.userName, 24.dp)
        }
        if (handle != null)
            Box(handle.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                MusicIcon("grabber", Color.White.copy(alpha = .35f), 19.dp)
            }
    }
}
