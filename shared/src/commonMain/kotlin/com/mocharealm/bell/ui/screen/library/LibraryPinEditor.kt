// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.MusicIcon
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
internal fun LibraryPinEditor(
    model: BellStore,
    state: BellState,
    pins: List<Item>,
    columns: Int,
    editing: LibraryEditing,
) {
    val byId = pins.associateBy { it.id }
    val ids =
        (editing.pinOrder + byId.keys).distinct().filter {
            it in byId && it !in editing.removedPins
        }
    if (ids.isEmpty()) return
    val wiggle =
        rememberInfiniteTransition(label = "Pin edit")
            .animateFloat(
                initialValue = -1.4f,
                targetValue = 1.4f,
                animationSpec =
                    infiniteRepeatable(
                        tween(126, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse,
                    ),
                label = "Pin rotation",
            )
    val moveEarlier = stringResource(Res.string.move_pin_earlier)
    val moveLater = stringResource(Res.string.move_pin_later)
    val unpin = stringResource(Res.string.unpin)
    val gridState = rememberLazyGridState()
    val reorder =
        rememberReorderableLazyGridState(gridState) { from, to ->
            editing.movePin(ids, ids.indexOf(from.key), ids.indexOf(to.key))
        }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontalGap = 12.dp
        val verticalGap = 20.dp
        val tileWidth = (maxWidth - horizontalGap * (columns - 1)) / columns
        val captionHeight = with(LocalDensity.current) { 19.sp.toDp() } + 5.dp
        val rows = (ids.size + columns - 1) / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            userScrollEnabled = false,
            modifier = Modifier.height((tileWidth + captionHeight + verticalGap) * rows + 22.dp),
            contentPadding = PaddingValues(top = 9.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(horizontalGap),
            verticalArrangement = Arrangement.spacedBy(verticalGap),
        ) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                ReorderableItem(reorder, key = id) { dragging ->
                    Box(
                        Modifier.fillMaxWidth()
                            .longPressDraggableHandle(enabled = ids.size > 1)
                            .semantics {
                                customActions = buildList {
                                    if (index > 0)
                                        add(
                                            CustomAccessibilityAction(moveEarlier) {
                                                editing.movePin(ids, index, index - 1)
                                            }
                                        )
                                    if (index < ids.lastIndex)
                                        add(
                                            CustomAccessibilityAction(moveLater) {
                                                editing.movePin(ids, index, index + 1)
                                            }
                                        )
                                }
                            }
                    ) {
                        AlbumTile(
                            model,
                            state,
                            byId.getValue(id),
                            Modifier.fillMaxWidth(),
                            compact = true,
                            enabled = false,
                            artworkModifier =
                                Modifier.graphicsLayer {
                                    rotationZ =
                                        if (dragging) 0f
                                        else wiggle.value * if (index % 2 == 0) 1f else -1f
                                },
                        )
                        Box(
                            Modifier.offset(x = (-9).dp, y = (-9).dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC86868B))
                                .clickable(role = Role.Button) { editing.removePin(id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            MusicIcon("minus", Color.White, 16.dp, unpin)
                        }
                    }
                }
            }
        }
    }
}
