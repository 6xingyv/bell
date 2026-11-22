// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val LyricsCharacterLimit = 149

internal class LyricsExcerpt(val lines: List<String>, val initialIndex: Int)

internal fun lyricExcerptAt(lines: List<String>, index: Int): LyricsExcerpt? {
    val rows = lines.mapIndexedNotNull { sourceIndex, line ->
        line.trim().takeIf { it.isNotEmpty() }?.let { sourceIndex to it }
    }
    val initial = rows.indexOfFirst { it.first == index }
    return if (initial < 0) null else LyricsExcerpt(rows.map { it.second }, initial)
}

internal fun List<ISyncedLine>.excerptFrom(pressed: ISyncedLine): LyricsExcerpt? {
    val index = indexOfFirst { it === pressed }.takeIf { it >= 0 } ?: indexOf(pressed)
    if (index < 0) return null
    val lines = map { line ->
        when (line) {
            is SyncedLine -> line.content
            is KaraokeLine -> line.syllables.joinToString("") { it.content }
            else -> ""
        }
    }
    return lyricExcerptAt(lines, index)
}

internal fun List<ISyncedLine>.excerptAtPosition(position: Int): LyricsExcerpt? {
    val index = indexOfLast { it.start <= position }.coerceAtLeast(0)
    return (index downTo 0).firstNotNullOfOrNull { getOrNull(it)?.let(::excerptFrom) }
        ?: firstNotNullOfOrNull { excerptFrom(it) }
}

@Composable
internal fun LyricsShareSheet(
    model: BellStore,
    state: BellState,
    item: Item,
    excerpt: LyricsExcerpt,
    visible: Boolean,
    source: Rect?,
    sourceFontSize: TextUnit,
    mesh: GraphicsLayer,
    dismiss: () -> Unit,
    share: (String) -> Unit,
) {
    var selection by remember(excerpt) { mutableStateOf(excerpt.initialIndex..excerpt.initialIndex) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = excerpt.initialIndex)
    val characters = remember(excerpt, selection) {
        selection.sumOf { excerpt.lines[it].length }
    }
    val title = stringResource(Res.string.share_lyrics)
    val shareLabel = stringResource(Res.string.share)
    var destination by remember { mutableStateOf<Rect?>(null) }
    val flight = remember { Animatable(if (source == null) 1f else 0f) }
    val hasDestination = destination != null
    LaunchedEffect(visible, hasDestination) {
        if (!visible || source == null) flight.snapTo(1f)
        else if (hasDestination) flight.animateTo(1f, tween(360))
    }
    LyricsShareModal(title, visible, dismiss, header = {
        val foreground = if (LocalMusicDark.current) Color.White else Color.Black
        Row(
            Modifier.widthIn(max = 230.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArtwork(model, state, item, Modifier.size(44.dp))
            Column {
                val count = selection.count()
                MusicText(
                    pluralStringResource(Res.plurals.lyrics_lines_selected, count, count),
                    16,
                    weight = FontWeight.SemiBold,
                )
                MusicText(
                    stringResource(Res.string.lyrics_character_count, characters, LyricsCharacterLimit),
                    13,
                    foreground.copy(alpha = .55f),
                )
            }
        }
    }, overlay = {
        val target = destination
        if (visible && source != null && target != null && flight.value < 1f) {
            var rootOnScreen by remember { mutableStateOf<Offset?>(null) }
            Box(
                Modifier.matchParentSize().onGloballyPositioned { coordinates ->
                    rootOnScreen = coordinates.localToScreen(Offset.Zero).takeIf { it.isFinite }
                }
            ) {
                rootOnScreen?.let { origin ->
                    val progress = flight.value
                    val bounds = androidx.compose.ui.geometry.lerp(source, target, progress)
                    val start = bounds.topLeft - origin
                    val density = LocalDensity.current
                    val fontSize = if (sourceFontSize.isSp)
                        (sourceFontSize.value + (18f - sourceFontSize.value) * progress).toInt()
                    else 18
                    Box(
                        Modifier.absoluteOffset { androidx.compose.ui.unit.IntOffset(start.x.toInt(), start.y.toInt()) }
                            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                            .clip(musicShape(12.dp))
                            .lyricsSelectionMesh(mesh, { start.y }, 1f)
                            .graphicsLayer { alpha = (1f - progress).times(5f).coerceIn(0f, 1f) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        MusicText(
                            excerpt.lines[excerpt.initialIndex], fontSize, Color.White,
                            FontWeight.Medium, lines = Int.MAX_VALUE,
                        )
                    }
                }
            }
        }
    }) {
        val dark = LocalMusicDark.current
        val foreground = if (dark) Color.White else Color.Black
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = list,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(excerpt.lines) { index, text ->
                val selected = index in selection
                val topRadius by animateDpAsState(
                    if (selected && index > selection.first) 3.dp else 12.dp, label = "top corners",
                )
                val bottomRadius by animateDpAsState(
                    if (selected && index < selection.last) 3.dp else 12.dp, label = "bottom corners",
                )
                val fill by androidx.compose.animation.core.animateFloatAsState(
                    if (selected) 1f else 0f, label = "lyric selection",
                )
                var rowTop by remember { mutableFloatStateOf(0f) }
                MusicText(
                    text,
                    18,
                    if (selected) Color.White else foreground,
                    weight = FontWeight.Medium,
                    lines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            rowTop = coordinates.positionInRoot().y
                            if (index == excerpt.initialIndex && flight.value < 1f) {
                                val start = coordinates.localToScreen(Offset.Zero)
                                val end = coordinates.localToScreen(
                                    Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                                )
                                if (start.isFinite && end.isFinite && end.x > start.x && end.y > start.y)
                                    destination = Rect(start, end)
                            }
                        }
                        .graphicsLayer {
                            if (index == excerpt.initialIndex && source != null)
                                alpha = ((flight.value - .8f) * 5f).coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius))
                        .lyricsSelectionMesh(mesh, { rowTop }, fill)
                        .semantics { this.selected = selected }
                        .clickable {
                            val next = when {
                                index < selection.first -> index..selection.last
                                index > selection.last -> selection.first..index
                                selection.first == selection.last -> selection
                                index == selection.last -> selection.first until index
                                else -> (index + 1)..selection.last
                            }
                            if (next.sumOf { excerpt.lines[it].length } <= LyricsCharacterLimit)
                                selection = next
                            else if (!selected && characters > LyricsCharacterLimit)
                                selection = index..index
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = foreground.copy(alpha = .12f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            IconTouch("square.and.arrow.up", shareLabel, {
                val lyrics = selection.joinToString("\n") { excerpt.lines[it] }
                val credit = listOf(item.name, item.artist).filter { it.isNotBlank() }.joinToString(" · ")
                share(if (credit.isEmpty()) lyrics else "$lyrics\n\n$credit")
            }, foreground)
        }
    }
}

private fun Modifier.lyricsSelectionMesh(
    mesh: GraphicsLayer,
    topInViewport: () -> Float,
    opacity: Float,
) = drawWithCache {
    val fade = Paint().apply { alpha = opacity }
    onDrawWithContent {
        if (opacity > 0f) {
            drawIntoCanvas { canvas ->
                if (opacity < 1f) canvas.saveLayer(Rect(Offset.Zero, size), fade)
                try {
                    drawRect(Color(0xFF34363C))
                    if (mesh.size.width > 0 && mesh.size.height > 0) {
                        val scale = maxOf(size.width / mesh.size.width, 1f)
                        withTransform({
                            translate((size.width - mesh.size.width * scale) / 2f, -topInViewport())
                            scale(scale, scale, Offset.Zero)
                        }) { drawLayer(mesh) }
                        drawRect(Color.Black, alpha = .24f)
                    }
                } finally {
                    if (opacity < 1f) canvas.restore()
                }
            }
        }
        drawContent()
    }
}

@Composable
internal expect fun LyricsShareModal(
    title: String,
    visible: Boolean,
    dismiss: () -> Unit,
    header: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)
