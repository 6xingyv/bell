// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.snapping.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.composables.*
import com.mocharealm.bell.ui.screen.player.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

internal const val QueueCurrentHeaderKey = "queue-current"

internal fun queueCurrentIndex(historySize: Int) = if (historySize > 0) historySize + 2 else 0

// The title first enters from above, then stays pinned while older songs pass below it.
internal fun queueHistoryHeaderOffset(
    list: LazyListState,
    historySize: Int,
    heightPx: Float,
): Float {
    if (historySize == 0) return -heightPx
    val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == QueueCurrentHeaderKey }
    return current?.let { (it.offset - heightPx).coerceIn(-heightPx, 0f) }
        ?: if (list.firstVisibleItemIndex < queueCurrentIndex(historySize)) 0f else -heightPx
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicPlayerQueue(
    model: BellStore,
    state: BellState,
    viewModel: PlayerViewModel,
    list: LazyListState,
    modifier: Modifier,
    collapsedHeight: Dp,
    drag: QueueDragState,
    tabletLayout: Boolean = false,
) {
    val historyLabel = stringResource(Res.string.playback_history)
    val queueLabel = stringResource(Res.string.queue_title)
    val continueLabel = stringResource(Res.string.continue_playing)
    val source =
        state.queueContext
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(Res.string.queue_source, it) }
            ?: stringResource(Res.string.recommended_continuation)
    val clear = stringResource(Res.string.clear)
    val cancel = stringResource(Res.string.cancel)
    val addSongs = stringResource(Res.string.add_songs_to_queue)
    val shuffle = stringResource(Res.string.shuffle)
    val repeat =
        stringResource(if (state.repeatOne) Res.string.repeat_one else Res.string.repeat_all)
    val autoPlay = stringResource(Res.string.autoplay)
    val autoMix = stringResource(Res.string.automix)
    val dragLabel = stringResource(Res.string.reorder_song)
    val moveUp = stringResource(Res.string.move_song_up)
    val moveDown = stringResource(Res.string.move_song_down)
    val historyMessage = stringResource(Res.string.clear_history_confirmation)
    val queueMessage = stringResource(Res.string.clear_queue_confirmation)
    val presentation by viewModel.presentation.collectAsState()
    var retainedConfirmation by remember { mutableStateOf(QueueConfirmation.History) }
    SideEffect { presentation.queueConfirmation?.let { retainedConfirmation = it } }
    QueueConfirmationAlert(
        presentation.queueConfirmation != null,
        if ((presentation.queueConfirmation ?: retainedConfirmation) == QueueConfirmation.History)
            historyMessage
        else queueMessage,
        cancel,
        clear,
        { viewModel.accept(PlayerIntent.DismissClear) },
        { viewModel.accept(PlayerIntent.ClearConfirmed) },
    )

    val density = LocalDensity.current
    val collapsedPx = with(density) { collapsedHeight.toPx() }
    val edge = with(density) { 56.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(state)
    val onMove: (Long, Long) -> Unit = { from, to ->
        viewModel.accept(PlayerIntent.MoveQueue(from, to))
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val move by rememberUpdatedState(onMove)
    LaunchedEffect(drag.dragging) {
        if (drag.dragging) drag.autoScroll(edge) { a, b -> move(a, b) }
    }
    DisposableEffect(drag) {
        onDispose {
            drag.cancel()
            viewModel.accept(PlayerIntent.QueueDragging(false))
        }
    }
    LaunchedEffect(state.queueKeys, state.queueIndex) {
        drag.key?.let { key ->
            if (state.queueKeys.indexOf(key) <= state.queueIndex) {
                drag.cancel()
                viewModel.accept(PlayerIntent.QueueDragging(false))
            }
        }
    }
    fun begin(key: Long, item: Item) {
        if (drag.begin(key, item)) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.accept(PlayerIntent.QueueDragging(true))
        }
    }
    fun release() {
        viewModel.accept(PlayerIntent.QueueDragging(false))
        drag.finish(collapsedPx)
    }
    val manualEnd = (state.queueIndex + 1 + state.manualQueueCount).coerceIn(0, state.queue.size)
    val showHistory by
        remember(list, state.history.size) {
            derivedStateOf {
                state.history.isNotEmpty() &&
                    list.firstVisibleItemIndex < queueCurrentIndex(state.history.size)
            }
        }
    var historyExpanded by remember { mutableStateOf(true) }

    val fling = rememberSnapFlingBehavior(list, snapPosition = SnapPosition.Start)
    BoxWithConstraints(modifier) {
        // Even with no following songs, the current item can reach (and keep) offset zero.
        val remaining = (state.queue.size - state.queueIndex - 1).coerceAtLeast(0)
        val hasContinuation = manualEnd < state.queue.size || state.autoPlay
        var measuredContinuation by
            remember(source, state.manualQueueCount) {
                mutableStateOf(52.dp + (if (state.manualQueueCount > 0) 18.dp else 0.dp))
            }
        val continuationHeight = if (hasContinuation) measuredContinuation else 0.dp
        val contentHeight =
            72.dp +
                74.dp +
                56.dp * remaining +
                (if (state.manualQueueCount > 0) 96.dp else 0.dp) +
                continuationHeight
        val endSpace = (maxHeight - contentHeight - 16.dp).coerceAtLeast(0.dp)
        val historyHeight = with(density) { 40.dp.toPx() }
        val historyOffset = queueHistoryHeaderOffset(list, state.history.size, historyHeight)
        LazyColumn(
            state = list,
            flingBehavior = fling,
            modifier = Modifier.fillMaxSize().clipToBounds(),
            contentPadding =
                PaddingValues(
                    start = 32.dp,
                    end = 32.dp,
                    bottom = if (tabletLayout) 88.dp else 16.dp,
                ),
        ) {
            if (state.history.isNotEmpty()) {
                item(key = "history-heading") { Spacer(Modifier.height(40.dp)) }
                items(state.history, key = { "history:${it.key}" }) { entry ->
                    if (historyExpanded)
                        PlayerQueueRow(
                            model,
                            state,
                            entry.item,
                            Modifier.animateItem().queueEdgeFade(list, "history:${entry.key}") {
                                showHistory
                            },
                            play = { viewModel.accept(PlayerIntent.PlayHistory(entry.key)) },
                        )
                    else Spacer(Modifier.fillMaxWidth().height(0.dp))
                }
            }
            if (state.history.isNotEmpty())
                item(key = "history-reveal-space") {
                    Spacer(Modifier.height(if (tabletLayout) 0.dp else 40.dp))
                }
            item(key = QueueCurrentHeaderKey) {
                Spacer(Modifier.fillMaxWidth().height(if (tabletLayout) 0.dp else 72.dp))
            }
            stickyHeader(key = "queue-modes") {
                Row(
                    Modifier.fillMaxWidth()
                        .queueEdgeFade(list, "queue-modes") { showHistory }
                        .padding(top = if (tabletLayout) 0.dp else 16.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlayerQueueToggle("shuffle", shuffle, state.shuffle, Modifier.weight(1f)) {
                        viewModel.accept(PlayerIntent.Shuffle)
                    }
                    PlayerQueueToggle(
                        if (state.repeatOne) "repeatOne" else "repeat",
                        repeat,
                        state.repeat,
                        Modifier.weight(1f),
                    ) {
                        viewModel.accept(PlayerIntent.Repeat)
                    }
                    PlayerQueueToggle("autoplay", autoPlay, state.autoPlay, Modifier.weight(1f)) {
                        viewModel.accept(PlayerIntent.AutoPlay)
                    }
                    PlayerQueueToggle("automix", autoMix, state.autoMix, Modifier.weight(1f)) {
                        viewModel.accept(PlayerIntent.AutoMix)
                    }
                }
            }
            if (state.manualQueueCount > 0)
                stickyHeader(key = "manual-heading") {
                    QueueHeading(
                        queueLabel,
                        clear,
                        onClear = {
                            viewModel.accept(PlayerIntent.ConfirmClear(QueueConfirmation.Queue))
                        },
                    )
                }
            fun LazyListScope.songs(from: Int, to: Int) {
                items((from until to).toList(), key = { queueSongKey(state.queueKeys[it]) }) { index
                    ->
                    val item = state.queue[index]
                    val key = state.queueKeys[index]
                    val handle =
                        Modifier.pointerInput(key) {
                                detectDragGestures(
                                    onDragStart = { begin(key, item) },
                                    onDragEnd = { release() },
                                    onDragCancel = { release() },
                                ) { change, delta ->
                                    if (drag.dragging) {
                                        change.consume()
                                        drag.drag(delta.y) { a, b -> move(a, b) }
                                    }
                                }
                            }
                            .semantics {
                                contentDescription = dragLabel
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(moveUp) {
                                            val s = latest
                                            val i = s.queueKeys.indexOf(key)
                                            if (i > s.queueIndex + 1) {
                                                move(key, s.queueKeys[i - 1])
                                                true
                                            } else false
                                        },
                                        CustomAccessibilityAction(moveDown) {
                                            val s = latest
                                            val i = s.queueKeys.indexOf(key)
                                            if (i >= 0 && i < s.queue.lastIndex) {
                                                move(key, s.queueKeys[i + 1])
                                                true
                                            } else false
                                        },
                                    )
                            }
                    PlayerQueueRow(
                        model,
                        state,
                        item,
                        Modifier.animateItem(
                                placementSpec = spring(dampingRatio = 1f, stiffness = 420f)
                            )
                            .queueEdgeFade(list, queueSongKey(key)) { showHistory }
                            .drawWithContent { if (drag.key != key) drawContent() }
                            .pointerInput(key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { begin(key, item) },
                                    onDragEnd = { release() },
                                    onDragCancel = { release() },
                                ) { change, delta ->
                                    change.consume()
                                    drag.drag(delta.y) { a, b -> move(a, b) }
                                }
                            },
                        handle = handle,
                        entry = state.listening.entries.getOrNull(index),
                        play = { viewModel.accept(PlayerIntent.Play(state.queue, index)) },
                    )
                }
            }
            songs((state.queueIndex + 1).coerceAtLeast(0), manualEnd)
            if (state.manualQueueCount > 0)
                item(key = "queue-add-songs") {
                    Row(
                        Modifier.fillMaxWidth()
                            .height(56.dp)
                            .queueEdgeFade(list, "queue-add-songs") { showHistory }
                            .clickable {
                                model.accept(BellIntent.Dialog(LibraryDialog.AddToQueue))
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp)
                                .clip(musicShape(4.dp))
                                .background(Color.White.copy(alpha = .09f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            MusicIcon("plus", Color.White.copy(alpha = .6f), 24.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        MusicText(addSongs, 17, Color.White.copy(alpha = .65f))
                    }
                }
            if (hasContinuation)
                stickyHeader(key = "continue-heading") {
                    Column(
                        Modifier.fillMaxWidth()
                            .onSizeChanged {
                                measuredContinuation = with(density) { it.height.toDp() }
                            }
                            .queueEdgeFade(list, "continue-heading") { showHistory }
                            .padding(
                                top = if (state.manualQueueCount > 0) 18.dp else 0.dp,
                                bottom = 12.dp,
                            )
                    ) {
                        MusicText(
                            continueLabel,
                            17,
                            Color.White.copy(alpha = .65f),
                            FontWeight.SemiBold,
                        )
                        MusicText(source, 12, Color.White.copy(alpha = .4f))
                    }
                }
            songs(manualEnd, state.queue.size)
            item(key = "queue-bottom-space") { Spacer(Modifier.height(endSpace)) }
        }
        if (showHistory)
            Box(
                Modifier.offset { IntOffset(0, historyOffset.roundToInt()) }
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                QueueHeading(
                    historyLabel,
                    clear,
                    onClear = {
                        viewModel.accept(PlayerIntent.ConfirmClear(QueueConfirmation.History))
                    },
                    expanded = historyExpanded,
                    onToggle = { historyExpanded = !historyExpanded },
                )
            }
    }
}

@Composable
internal fun QueueDragOverlay(
    model: BellStore,
    state: BellState,
    drag: QueueDragState,
    viewportTop: Dp,
    viewportStart: Dp = 0.dp,
    viewportWidth: Dp = Dp.Unspecified,
) {
    val density = LocalDensity.current
    val lift by animateFloatAsState(if (drag.dragging) 1f else 0f, tween(220), label = "queue lift")
    drag.item
        ?.takeIf { drag.key != null }
        ?.let { item ->
            val inset = 4.dp * lift
            QueueDraggedSurface(
                Modifier.offset(x = viewportStart)
                    .then(
                        if (viewportWidth == Dp.Unspecified) Modifier.fillMaxWidth()
                        else Modifier.width(viewportWidth)
                    )
                    .offset {
                        IntOffset(
                            0,
                            (with(density) { viewportTop.toPx() - inset.toPx() } + drag.top)
                                .roundToInt(),
                        )
                    }
                    .fillMaxWidth()
                    .height(56.dp + inset * 2)
                    .graphicsLayer {
                        scaleX = 1f + .012f * lift
                        scaleY = 1f + .012f * lift
                    },
                lift,
            ) {
                PlayerQueueRow(
                    model,
                    state,
                    item,
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = inset),
                    handle = Modifier,
                    entry = state.listening.entries.getOrNull(state.queueKeys.indexOf(drag.key)),
                    play = {},
                )
            }
        }
}

@Composable
private fun QueueHeading(
    title: String,
    clear: String,
    onClear: () -> Unit,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicText(title, 17, Color.White.copy(alpha = .65f), FontWeight.SemiBold)
            if (expanded != null)
                MusicIcon(
                    "chevron",
                    Color.White.copy(alpha = .5f),
                    14.dp,
                    modifier =
                        Modifier.padding(start = 8.dp).graphicsLayer {
                            rotationZ = if (expanded) 90f else 0f
                        },
                )
        }
        MusicText(
            clear,
            17,
            Color.White.copy(alpha = .5f),
            modifier = Modifier.clickable(onClick = onClear).padding(vertical = 8.dp),
        )
    }
}
