// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/** Only the clipped edge item needs a small offscreen layer, never the entire queue. */
internal fun Modifier.queueEdgeFade(list: LazyListState, key: Any, historyVisible: () -> Boolean) =
    this.graphicsLayer {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            val top =
                if (historyVisible()) 52.dp.toPx()
                else if (list.firstVisibleItemScrollOffset > 0) 16.dp.toPx() else 0f
            val bottom = list.layoutInfo.viewportEndOffset - 24.dp.toPx()
            compositingStrategy =
                if (item != null && (item.offset < top || item.offset + item.size > bottom))
                    CompositingStrategy.Offscreen
                else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            val item =
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                    ?: return@drawWithContent
            val top =
                if (historyVisible()) 52.dp.toPx()
                else if (list.firstVisibleItemScrollOffset > 0) 16.dp.toPx() else 0f
            val extent = list.layoutInfo.viewportEndOffset.toFloat()
            val bottom = extent - 24.dp.toPx()
            if (item.offset >= top && item.offset + item.size <= bottom) return@drawWithContent
            drawRect(
                Brush.verticalGradient(
                    0f to if (top > 0f) Color.Transparent else Color.White,
                    (top / extent).coerceIn(0f, 1f) to Color.White,
                    (bottom / extent).coerceIn(0f, 1f) to Color.White,
                    1f to Color.Transparent,
                    startY = -item.offset.toFloat(),
                    endY = extent - item.offset,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
