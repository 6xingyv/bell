// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AlbumTile
import com.mocharealm.bell.ui.composables.MusicIcon
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun LibraryPinGrid(
    model: BellStore,
    state: BellState,
    pins: List<Item>,
    columns: Int,
    editing: LibraryEditing?,
) {
    val editable = editing?.mode == LibraryEditMode.Pins
    val byId = remember(pins) { pins.associateBy { it.id } }
    val ids =
        if (editable) {
            (editing.pinOrder + byId.keys).distinct().filter {
                it in byId && it !in editing.removedPins
            }
        } else byId.keys.toList()
    val currentIds by rememberUpdatedState(ids)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val slots = remember { mutableListOf<Rect>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val wigglePhase = remember { Animatable(0f) }
    LaunchedEffect(editable) {
        if (editable) {
            wigglePhase.snapTo(0f)
            wigglePhase.animateTo(
                1f,
                infiniteRepeatable(tween(280, easing = LinearEasing)),
            )
        } else {
            settleJob?.cancel()
            draggedId = null
            dragPosition = Offset.Zero
        }
    }
    val moveEarlier = stringResource(Res.string.move_pin_earlier)
    val moveLater = stringResource(Res.string.move_pin_later)
    val unpin = stringResource(Res.string.unpin)
    LookaheadScope {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                ids.forEachIndexed { index, id ->
                    key(id) {
                        val dragging = draggedId == id
                        val wiggleOffset = remember(id) { (id.hashCode() and 255) / 256f }
                        val wiggleAmplitude by
                            animateFloatAsState(
                                targetValue = if (editable && !dragging) .8f else 0f,
                                animationSpec = tween(120, easing = FastOutSlowInEasing),
                                label = "Pin rotation amplitude",
                            )
                        val gestures =
                            if (editable) {
                                Modifier.pointerInput(id, haptics) {
                                        var order = emptyList<String>()
                                        var dragIndex = -1
                                        fun settle() {
                                            if (draggedId != id) return
                                            val start = dragPosition
                                            val target =
                                                slots.getOrNull(dragIndex)?.topLeft ?: start
                                            settleJob = scope.launch {
                                                animate(
                                                    0f,
                                                    1f,
                                                    animationSpec =
                                                        tween(180, easing = FastOutSlowInEasing),
                                                ) { progress, _ ->
                                                    dragPosition =
                                                        start + (target - start) * progress
                                                }
                                                draggedId = null
                                            }
                                        }
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                order = currentIds
                                                dragIndex = order.indexOf(id)
                                                val slot = slots.getOrNull(dragIndex)
                                                if (slot != null) {
                                                    settleJob?.cancel()
                                                    dragPosition = slot.topLeft
                                                    draggedId = id
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                }
                                            },
                                            onDragCancel = { settle() },
                                            onDragEnd = { settle() },
                                        ) { change, amount ->
                                            if (draggedId == id) {
                                                change.consume()
                                                dragPosition += amount
                                                val slot = slots.getOrNull(dragIndex)
                                                if (slot != null) {
                                                    val center =
                                                        dragPosition +
                                                            Offset(
                                                                slot.width / 2f,
                                                                slot.height / 2f,
                                                            )
                                                    val target =
                                                        slots.indices.minByOrNull {
                                                            (slots[it].center - center)
                                                                .getDistanceSquared()
                                                        } ?: dragIndex
                                                    if (editing.movePin(order, dragIndex, target)) {
                                                        order = editing.pinOrder
                                                        dragIndex = target
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.SegmentFrequentTick
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
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
                            } else Modifier
                        Box(
                            Modifier.then(gestures)
                                .then(
                                    if (dragging) Modifier
                                    else Modifier.animateBounds(this@LookaheadScope)
                                )
                        ) {
                            AlbumTile(
                                model,
                                state,
                                byId.getValue(id),
                                Modifier.fillMaxWidth().graphicsLayer {
                                    rotationZ =
                                        wiggleAmplitude *
                                            sin((wigglePhase.value + wiggleOffset) * 2.0 * PI)
                                                .toFloat()
                                },
                                compact = true,
                                enabled = !editable,
                            )
                            AnimatedVisibility(
                                visible = editable,
                                modifier = Modifier.offset(x = (-9).dp, y = (-9).dp),
                                enter = fadeIn(tween(140)),
                                exit = fadeOut(tween(110)),
                            ) {
                                Box(
                                    Modifier.size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xCC86868B))
                                        .clickable(enabled = editable, role = Role.Button) {
                                            editing?.removePin(id)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MusicIcon("minus", Color.White, 16.dp, unpin)
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val horizontalGap = 12.dp.roundToPx()
            val verticalGap = 20.dp.roundToPx()
            val width = constraints.maxWidth
            val available = (width - horizontalGap * (columns - 1)).coerceAtLeast(0)
            val rows =
                measurables.chunked(columns).map { row ->
                    row.mapIndexed { index, tile ->
                        val cellWidth =
                            available * (index + 1) / columns - available * index / columns
                        tile.measure(Constraints.fixedWidth(cellWidth))
                    }
                }
            val height =
                rows.sumOf { row -> row.maxOf { it.height } + verticalGap } +
                    if (rows.isEmpty()) 0 else 12.dp.roundToPx()
            val realPlacement = !isLookingAhead
            val rtl = layoutDirection == LayoutDirection.Rtl
            layout(width, constraints.constrainHeight(height)) {
                if (realPlacement) slots.clear()
                var index = 0
                var y = 0
                rows.forEach { row ->
                    var x = 0
                    row.forEach { tile ->
                        val left = if (rtl) width - x - tile.width else x
                        val origin = Offset(left.toFloat(), y.toFloat())
                        if (realPlacement) {
                            slots += Rect(origin, Size(tile.width.toFloat(), tile.height.toFloat()))
                        }
                        if (realPlacement && draggedId == ids[index]) {
                            tile.placeRelativeWithLayer(x, y, zIndex = 1f) {
                                val offset = dragPosition - origin
                                translationX = offset.x
                                translationY = offset.y
                            }
                        } else {
                            tile.placeRelative(x, y)
                        }
                        index++
                        x += tile.width + horizontalGap
                    }
                    y += row.maxOf { it.height } + verticalGap
                }
            }
        }
    }
}
