// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.drawWithMusicPlus
import com.mocharealm.bell.ui.utils.rememberMusicPlusPaint
import kotlin.math.abs
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

@Stable
private class MusicSwipeOwner {
    var id by mutableStateOf<String?>(null)
    var visibility by mutableFloatStateOf(0f)
}

private val LocalOpenMusicSwipe = staticCompositionLocalOf { MusicSwipeOwner() }
private val AppleRed = Color(0xFFFF3B30)
private val ApplePurple = Color(0xFFAF52DE)
private val AppleYellow = Color(0xFFFFCC00)
private val DownloadBlue = Color(0xFF0092FF)

@Composable
internal fun Modifier.musicSwipeDivider(
    beforeId: String? = null,
    afterId: String? = null,
): Modifier {
    val owner = LocalOpenMusicSwipe.current
    return graphicsLayer {
        alpha =
            if (owner.id != null && (owner.id == beforeId || owner.id == afterId))
                1f - owner.visibility
            else 1f
    }
}

/** The page gutter opens first; action circles grow in the space left behind it. */
@Composable
internal fun MusicSwipeRow(
    model: BellStore,
    state: BellState,
    item: Item,
    content: @Composable () -> Unit,
) {
    val owner = LocalOpenMusicSwipe.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.toPx() }
    val revealDistance = with(density) { 16.dp.toPx() }
    var offset by remember(item.id) { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf(IntSize(1, 1)) }
    val downloaded = item.id in state.downloaded
    val hasManualQueue = state.manualQueueCount > 0
    val nextLabel = stringResource(Res.string.play_next)
    val lastLabel = stringResource(Res.string.play_last)
    val downloadLabel = stringResource(Res.string.download)
    val deleteLabel = stringResource(Res.string.delete_from_library)
    val plus = LocalMusicPlayerPlus.current
    val plusPaint = rememberMusicPlusPaint()
    val fillShape = musicShape(26.dp * (abs(offset) / stepForCorners(density)).coerceIn(0f, 1f))
    var windowX by remember { mutableFloatStateOf(0f) }
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    fun update(value: Float) {
        offset = value
        if (owner.id == item.id) owner.visibility = (abs(value) / revealDistance).coerceIn(0f, 1f)
    }
    fun settle(target: Float) {
        settling?.cancel()
        settling = scope.launch {
            animate(offset, target, animationSpec = spring(dampingRatio = 1f, stiffness = 420f)) {
                value,
                _ ->
                update(value)
            }
            if (target == 0f && owner.id == item.id) {
                owner.visibility = 0f
                owner.id = null
            }
        }
    }
    fun close() {
        settle(0f)
    }
    fun next() {
        model.accept(BellIntent.PlayNext(item))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        close()
    }
    fun last() {
        model.accept(BellIntent.Enqueue(item))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        close()
    }
    fun download() {
        model.accept(BellIntent.Download(item))
        close()
    }
    fun delete() {
        model.accept(BellIntent.Dialog(LibraryDialog.Delete(item)))
        close()
    }
    LaunchedEffect(owner.id) { if (owner.id != item.id && !dragging) settle(0f) }
    DisposableEffect(item.id) {
        onDispose {
            settling?.cancel()
            if (owner.id == item.id) {
                owner.visibility = 0f
                owner.id = null
            }
        }
    }
    val width = dimensions.width.toFloat()
    val leadingGutter = windowX.coerceAtLeast(0f)
    val trailingGutter = (windowWidth - windowX - width).coerceAtLeast(0f)
    val diameter =
        minOf(with(density) { 52.dp.toPx() }, (dimensions.height - gap).coerceAtLeast(1f))
    val step = diameter + gap
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { dimensions = it }
            .onGloballyPositioned { windowX = it.positionInWindow().x }
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (abs(offset) < .01f) return@Canvas
            val left = -windowX + offset
            val right = windowWidth - windowX + offset
            if (right <= left) return@Canvas
            val outline =
                fillShape.createOutline(Size(right - left, size.height), layoutDirection, this)
            translate(left, 0f) {
                val overlay =
                    Color.White.copy(alpha = .16f * (abs(offset) / revealDistance).coerceIn(0f, 1f))
                if (plus) drawWithMusicPlus(plusPaint) { drawOutline(outline, overlay) }
                else drawOutline(outline, overlay, blendMode = BlendMode.SrcOver)
            }
        }
        if (abs(offset) > .01f)
            Box(Modifier.matchParentSize()) {
                val leading = offset > 0
                val two = if (leading) hasManualQueue else !downloaded
                val extent = abs(offset)
                val gutter = if (leading) leadingGutter else trailingGutter
                val available = (extent - gutter - gap).coerceAtLeast(0f)
                val reveal = gutter + step * (if (two) 2 else 1)
                val stretch =
                    if (two)
                        ((extent - reveal) / (width * .65f - reveal).coerceAtLeast(1f)).coerceIn(
                            0f,
                            1f,
                        )
                    else 0f
                val primaryVisibility = (available / diameter).coerceIn(0f, 1f)
                val secondaryVisibility =
                    if (two) ((available - step) / diameter).coerceIn(0f, 1f) * (1f - stretch)
                    else 0f
                val secondarySpace = step * secondaryVisibility
                val primaryWidth =
                    if (two && extent < reveal) diameter
                    else maxOf(diameter, available - secondarySpace)
                val alignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd
                val primaryIcon =
                    if (leading) {
                        if (hasManualQueue) "text.line.last.and.arrowtriangle.forward"
                        else "text.line.first.and.arrowtriangle.forward"
                    } else if (downloaded) "trash" else "arrowDown"
                val primaryColor =
                    if (leading) {
                        if (hasManualQueue) AppleYellow else ApplePurple
                    } else if (downloaded) AppleRed else DownloadBlue
                SwipeAction(
                    primaryIcon,
                    if (leading) {
                        if (hasManualQueue) lastLabel else nextLabel
                    } else if (downloaded) deleteLabel else downloadLabel,
                    primaryColor,
                    primaryVisibility,
                    leading,
                    with(density) { primaryWidth.toDp() },
                    with(density) { diameter.toDp() },
                    Modifier.align(alignment),
                ) {
                    if (leading) {
                        if (hasManualQueue) last() else next()
                    } else if (downloaded) delete() else download()
                }
                if (two && secondaryVisibility > 0f)
                    SwipeAction(
                        if (leading) "text.line.first.and.arrowtriangle.forward" else "trash",
                        if (leading) nextLabel else deleteLabel,
                        if (leading) ApplePurple else AppleRed,
                        secondaryVisibility,
                        leading,
                        with(density) { diameter.toDp() },
                        with(density) { diameter.toDp() },
                        Modifier.align(alignment)
                            .offset(
                                x =
                                    with(density) {
                                        ((primaryWidth + gap * secondaryVisibility) *
                                                (if (leading) 1 else -1))
                                            .toDp()
                                    }
                            ),
                    ) {
                        if (leading) next() else delete()
                    }
            }
        Box(
            Modifier.graphicsLayer { translationX = offset }
                .pointerInput(
                    item.id,
                    downloaded,
                    hasManualQueue,
                    width,
                    diameter,
                    leadingGutter,
                    trailingGutter,
                ) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settling?.cancel()
                            dragging = true
                            owner.id = item.id
                            update(offset)
                        },
                        onDragCancel = {
                            dragging = false
                            armed = false
                            settle(0f)
                        },
                        onDragEnd = {
                            dragging = false
                            if (armed) {
                                if (offset > 0) {
                                    if (hasManualQueue) last() else next()
                                } else if (downloaded) {
                                    if (model.canDelete(item)) delete() else close()
                                } else download()
                            } else {
                                val gutter = if (offset > 0) leadingGutter else trailingGutter
                                if (abs(offset) > gutter + gap + diameter * .5f)
                                    settle(
                                        if (offset > 0)
                                            leadingGutter + step * (if (hasManualQueue) 2 else 1)
                                        else -(trailingGutter + step * (if (downloaded) 1 else 2))
                                    )
                                else close()
                            }
                            armed = false
                        },
                    ) { change, delta ->
                        change.consume()
                        update((offset + delta).coerceIn(-width * .95f, width * .95f))
                        val threshold = abs(offset) > width * .65f
                        if (threshold && !armed)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        armed = threshold
                    }
                }
        ) {
            content()
        }
    }
}

private fun stepForCorners(density: androidx.compose.ui.unit.Density) =
    with(density) { 62.dp.toPx() }

@Composable
private fun SwipeAction(
    icon: String,
    label: String,
    color: Color,
    visibility: Float,
    leading: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    action: () -> Unit,
) {
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = visibility
                scaleY = visibility
                alpha = visibility
                transformOrigin = TransformOrigin(if (leading) 0f else 1f, .5f)
            }
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MusicIcon(icon, Color.White, 23.dp)
    }
}
