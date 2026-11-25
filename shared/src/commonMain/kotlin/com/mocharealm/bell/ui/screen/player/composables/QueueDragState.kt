// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import com.mocharealm.bell.domain.model.Item
import kotlin.math.abs
import kotlinx.coroutines.*

internal fun queueSongKey(key: Long) = "queue:$key"

/** Viewport coordinates keep the lifted row under the finger while rows scroll below it. */
@Stable
internal class QueueDragState(private val list: LazyListState, private val scope: CoroutineScope) {
    var key by mutableStateOf<Long?>(null)
        private set

    var item by mutableStateOf<Item?>(null)
        private set

    var dragging by mutableStateOf(false)
        private set

    var top by mutableFloatStateOf(0f)
        private set

    var height by mutableFloatStateOf(0f)
        private set

    private var settling: Job? = null
    private var scroll: Job? = null
    private var lastTarget: Long? = null

    fun begin(key: Long, item: Item): Boolean {
        val info =
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueSongKey(key) }
                ?: return false
        settling?.cancel()
        scroll?.cancel()
        this.key = key
        this.item = item
        top = info.offset.toFloat()
        height = info.size.toFloat()
        dragging = true
        lastTarget = null
        return true
    }

    fun drag(delta: Float, onMove: (Long, Long) -> Unit) {
        if (!dragging) return
        top += delta
        moveAtCenter(onMove)
    }

    fun moveAtCenter(onMove: (Long, Long) -> Unit) {
        val current = key ?: return
        val center = top + height / 2
        val target =
            list.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                val id =
                    (info.key as? String)
                        ?.takeIf { it.startsWith("queue:") }
                        ?.removePrefix("queue:")
                        ?.toLongOrNull()
                id != null &&
                    id != current &&
                    center >= info.offset &&
                    center < info.offset + info.size
            }
                ?: run {
                    lastTarget = null
                    return
                }
        val id = (target.key as String).removePrefix("queue:").toLong()
        if (lastTarget == id) return
        lastTarget = id
        onMove(current, id)
    }

    suspend fun autoScroll(edge: Float, onMove: (Long, Long) -> Unit) {
        var previous = 0L
        while (dragging) withFrameNanos { now ->
            val dt =
                if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(.032f)
            previous = now
            val extent = list.layoutInfo.viewportEndOffset.toFloat()
            val speed =
                when {
                    top < edge -> -((edge - top) / edge).coerceIn(0f, 2f) * 700f
                    top + height > extent - edge ->
                        ((top + height - (extent - edge)) / edge).coerceIn(0f, 2f) * 700f
                    else -> 0f
                }
            if (speed != 0f) {
                // scrollBy is suspending, so perform it after the frame callback.
                pendingScroll = speed * dt
            } else pendingScroll = 0f
        }
            .also {
                if (pendingScroll != 0f) {
                    list.scrollBy(pendingScroll)
                    moveAtCenter(onMove)
                }
            }
    }

    private var pendingScroll = 0f

    fun finish(collapsedHeight: Float) {
        val dropped = key ?: return
        dragging = false
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueSongKey(dropped) }
        val overflow = ((info?.offset ?: top.toInt()) + height - collapsedHeight).coerceAtLeast(0f)
        if (overflow > 0f) scroll = scope.launch { list.animateScrollBy(overflow) }
        settling?.cancel()
        settling = scope.launch {
            var previous = 0L
            var started = 0L
            var velocity = 0f
            while (isActive && key == dropped && !dragging) {
                var complete = false
                withFrameNanos { now ->
                    if (started == 0L) started = now
                    val dt =
                        if (previous == 0L) 0f
                        else ((now - previous) / 1_000_000_000f).coerceAtMost(.032f)
                    previous = now
                    val destination =
                        list.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == queueSongKey(dropped) }
                            ?.offset
                            ?.toFloat() ?: top
                    // Critically damped, retargeted every frame as the controls re-enter.
                    velocity += (420f * (destination - top) - 41f * velocity) * dt
                    top += velocity * dt
                    complete =
                        (now - started > 300_000_000L &&
                            abs(destination - top) < .25f &&
                            abs(velocity) < 1f) || now - started > 1_200_000_000L
                }
                if (complete) break
            }
            if (key == dropped && !dragging) {
                key = null
                item = null
            }
        }
    }

    fun cancel() {
        settling?.cancel()
        scroll?.cancel()
        dragging = false
        key = null
        item = null
    }
}
