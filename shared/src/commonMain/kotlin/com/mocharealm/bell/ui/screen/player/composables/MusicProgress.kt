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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.drawWithMusicPlus
import com.mocharealm.bell.ui.utils.rememberMusicPlusPaint
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicProgress(
    value: Float,
    change: (Float) -> Unit,
    finished: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = .7f),
    track: Color = Color.White.copy(alpha = .2f),
    label: String = stringResource(Res.string.playback_progress),
    indeterminate: Boolean = false,
) {
    val onChange by rememberUpdatedState(change)
    val onFinished by rememberUpdatedState(finished)
    val sweep =
        if (indeterminate) {
            val transition = rememberInfiniteTransition(label = "buffering progress")
            transition.animateFloat(
                -.35f,
                1f,
                infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "buffering sweep",
            )
        } else remember { mutableFloatStateOf(0f) }
    val plusPaint = rememberMusicPlusPaint()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo =
                    if (indeterminate) ProgressBarRangeInfo.Indeterminate
                    else ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress {
                    onChange(it)
                    onFinished()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = true)
                    first.consume()
                    onChange((first.position.x / size.width).coerceIn(0f, 1f))
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == first.id } ?: break
                        pointer.consume()
                        onChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                        pressed = pointer.pressed
                    }
                    onFinished()
                }
            }
    ) {
        val h = 7.dp.toPx()
        val y = (size.height - h) / 2
        drawWithMusicPlus(plusPaint) {
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        }
        val outline =
            Path().apply { addRoundRect(RoundRect(0f, y, size.width, y + h, CornerRadius(h / 2))) }
        clipPath(outline) {
            if (indeterminate)
                drawWithMusicPlus(plusPaint) {
                    drawRect(color, Offset(size.width * sweep.value, y), Size(size.width * .35f, h))
                }
            else if (value > 0)
                drawWithMusicPlus(plusPaint) {
                    drawRect(color, Offset(0f, y), Size(size.width * value.coerceIn(0f, 1f), h))
                }
        }
    }
}
