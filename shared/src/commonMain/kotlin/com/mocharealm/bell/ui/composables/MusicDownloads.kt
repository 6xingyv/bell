// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.musicBackground
import com.mocharealm.bell.ui.utils.musicPlus
import org.jetbrains.compose.resources.stringResource

internal val LocalMusicDownloadProgress =
    staticCompositionLocalOf<State<Map<String, Float>>?> { null }

@Composable
private fun downloadProgress(model: BellStore, id: String): State<Float?> {
    val progress = LocalMusicDownloadProgress.current ?: model.downloadProgress.collectAsState()
    return remember(progress, id) {
        derivedStateOf(structuralEqualityPolicy()) { progress.value[id] }
    }
}

@Composable
private fun DownloadRing(progress: Float, color: Color, modifier: Modifier) {
    val fraction by
        animateFloatAsState(
            progress.coerceIn(0f, 1f),
            tween(120, easing = LinearEasing),
            label = "download progress",
        )
    val plus = LocalMusicPlayerPlus.current
    Canvas(
        modifier.then(if (plus) Modifier.musicPlus() else Modifier).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        }
    ) {
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f + .5f
        val diameter = (size.minDimension - inset * 2).coerceAtLeast(0f)
        val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawCircle(color.copy(alpha = color.alpha * .25f), radius = diameter / 2f, style = stroke)
        drawArc(
            color,
            -90f,
            360f * fraction.coerceAtLeast(.025f),
            false,
            topLeft = origin,
            size = Size(diameter, diameter),
            style = stroke,
        )
    }
}

@Composable
internal fun MusicDownloadMarker(model: BellStore, state: BellState, item: Item, color: Color) {
    val progress by downloadProgress(model, item.id)
    if (progress != null)
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            DownloadRing(progress!!, color, Modifier.size(12.dp))
        }
    else if (item.id in state.downloaded)
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            MusicIcon("download", color, 13.dp, stringResource(Res.string.downloaded))
        }
}

@Composable
internal fun AlbumDownloadButton(
    model: BellStore,
    state: BellState,
    item: Item,
    tracks: List<Item>,
) {
    val progress by downloadProgress(model, item.id)
    val downloading = progress != null || item.id in state.downloading
    val complete = item.id in state.downloadedCollections
    val label =
        stringResource(
            if (downloading) Res.string.cancel_download
            else if (complete) Res.string.downloaded else Res.string.download
        )
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .musicBackground(Color.White.copy(alpha = .12f))
            .semantics { contentDescription = label }
            .clickable {
                model.accept(
                    if (downloading) BellIntent.CancelDownload(item)
                    else if (complete) BellIntent.RemoveDownload(item)
                    else BellIntent.Download(item, tracks)
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (downloading) {
            DownloadRing(progress ?: 0f, Color.White, Modifier.size(26.dp))
            MusicIcon("stop.fill", Color.White, 10.dp)
        } else MusicIcon(if (complete) "check" else "arrowDown", Color.White, 25.dp)
    }
}
