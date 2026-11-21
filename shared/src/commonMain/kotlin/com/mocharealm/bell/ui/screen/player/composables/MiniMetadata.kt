// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.utils.MusicLineHeightStyle
import com.mocharealm.bell.ui.utils.musicPlatformTextStyle
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Each field keeps its identity and fades outside its normal text bounds. */
@Composable
internal fun MusicMiniMetadata(
    model: BellStore,
    state: BellState,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val text_nothing_playing = stringResource(Res.string.nothing_playing)

    val scope = rememberCoroutineScope()
    var displayed by remember { mutableStateOf(state.current) }
    var incoming by remember { mutableStateOf<Item?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }
    var expectedId by remember { mutableStateOf<String?>(null) }
    var previousIndex by remember { mutableIntStateOf(state.queueIndex) }
    val currentState = rememberUpdatedState(state)
    val clock = rememberMetadataAnimationClock()
    fun settle(to: Float, commit: (() -> Unit)? = null) {
        animation?.cancel()
        busy = true
        animation = scope.launch {
            try {
                commit?.invoke()
                Animatable(offset).animateTo(to, spring(dampingRatio = 1f, stiffness = 420f)) {
                    offset = value
                }
                if (to != 0f) displayed = incoming
                incoming = null
                offset = 0f
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(state.current?.id) {
        val item = state.current
        if (item?.id == expectedId) {
            expectedId = null
            previousIndex = state.queueIndex
            return@LaunchedEffect
        }
        if (item?.id == displayed?.id) return@LaunchedEffect
        if (item == null || displayed == null) {
            animation?.cancel()
            displayed = item
            incoming = null
            offset = 0f
            busy = false
        } else {
            val forward =
                state.queueIndex == previousIndex + 1 ||
                    state.queueIndex == 0 && previousIndex == state.queue.lastIndex
            incoming = item
            offset = 0f
            settle(if (forward) -1f else 1f)
        }
        previousIndex = state.queueIndex
    }
    val base =
        MaterialTheme.typography.bodyLarge.copy(
            letterSpacing = 0.sp,
            platformStyle = musicPlatformTextStyle(),
            lineHeightStyle = MusicLineHeightStyle,
        )
    Column(
        modifier
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(model) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = true)
                    if (busy || currentState.value.current == null) return@awaitEachGesture
                    val sourceId = currentState.value.current?.id
                    val tracker = VelocityTracker()
                    tracker.addPosition(first.uptimeMillis, first.position)
                    var dragging = false
                    var cancelled = false
                    var targetIndex: Int? = null
                    var lastX = 0f
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (currentState.value.current?.id != sourceId) return@awaitEachGesture
                            val change = event.changes.firstOrNull { it.id == first.id } ?: break
                            val delta = change.position - first.position
                            if (!dragging && change.isConsumed) {
                                cancelled = true
                                break
                            }
                            if (
                                !dragging &&
                                    abs(delta.y) > viewConfiguration.touchSlop &&
                                    abs(delta.y) > abs(delta.x)
                            ) {
                                cancelled = true
                                break
                            }
                            if (
                                !dragging &&
                                    abs(delta.x) > viewConfiguration.touchSlop &&
                                    abs(delta.x) > abs(delta.y)
                            )
                                dragging = true
                            if (dragging) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (sign(delta.x) != sign(lastX)) {
                                    targetIndex = model.miniSwipeIndex(delta.x < 0f)
                                    incoming = targetIndex?.let {
                                        currentState.value.queue.getOrNull(it)
                                    }
                                }
                                lastX = delta.x
                                offset =
                                    if (targetIndex == null) (delta.x / width) * .18f
                                    else (delta.x / width).coerceIn(-1f, 1f)
                            }
                        } while (event.changes.any { it.pressed })
                        if (dragging) {
                            val velocity = tracker.calculateVelocity().x
                            val commit =
                                !cancelled &&
                                    targetIndex != null &&
                                    (abs(offset) > .3f ||
                                        abs(velocity) > 700.dp.toPx() &&
                                            sign(velocity) == sign(offset))
                            if (commit) {
                                val index = requireNotNull(targetIndex)
                                val queue = currentState.value.queue
                                val candidate = incoming
                                if (queue.getOrNull(index)?.id == candidate?.id) {
                                    expectedId = candidate?.id
                                    settle(if (offset < 0f) -1f else 1f) {
                                        model.accept(BellIntent.Play(queue, index))
                                    }
                                } else settle(0f)
                            } else settle(0f)
                        }
                    } finally {
                        // A parent vertical drag or disposal must not leave a preview attached.
                        if (dragging && animation?.isActive != true && offset != 0f) settle(0f)
                    }
                }
            },
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.listening.active)
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.mocharealm.bell.ui.composables.MusicIcon("shareplay", Color(0xFF30D158), 12.dp)
                Spacer(Modifier.width(4.dp))
                com.mocharealm.bell.ui.composables.MusicText(
                    stringResource(
                        Res.string.listening_count,
                        state.listening.group?.participants?.size ?: 1,
                    ),
                    12,
                    Color(0xFF30D158),
                )
            }
        MiniMetadataField(
            displayed?.name ?: text_nothing_playing,
            incoming?.name,
            base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            color,
            1f,
            clock,
            { offset },
            { width },
        )
        if (!state.listening.active)
            MiniMetadataField(
                displayed?.artist.orEmpty(),
                incoming?.artist,
                base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color,
                .65f,
                clock,
                { offset },
                { width },
            )
    }
}

@Composable
private fun MiniMetadataField(
    value: String,
    next: String?,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    alpha: Float,
    clock: IntState,
    offset: () -> Float,
    width: () -> Int,
) {
    val changed = next != null && next != value
    val origin = remember(value) { clock.intValue }
    Box(Modifier.fillMaxWidth()) {
        MusicPlayerMarqueeText(
            value,
            style,
            alpha,
            { clock.intValue - origin },
            color = color,
            blendMode = BlendMode.SrcOver,
            offsetX = { if (changed) offset() * width() else 0f },
        )
        if (changed)
            MusicPlayerMarqueeText(
                requireNotNull(next),
                style,
                alpha,
                { 0 },
                color = color,
                blendMode = BlendMode.SrcOver,
                offsetX = { (offset() + if (offset() <= 0f) 1f else -1f) * width() },
            )
    }
}
