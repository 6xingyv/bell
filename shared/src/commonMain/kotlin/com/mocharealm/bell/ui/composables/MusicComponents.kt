// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.nav.rememberArtworkLink
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.drawMusicTextLayers
import com.mocharealm.bell.ui.utils.musicBackground
import com.mocharealm.bell.ui.utils.musicColorGlyphPath
import com.mocharealm.bell.ui.utils.musicColorGlyphRanges
import com.mocharealm.bell.ui.utils.musicTextStyle
import com.mocharealm.bell.ui.utils.rememberMenuStrings
import com.mocharealm.bell.ui.utils.rememberMusicPlusPaint

internal val LocalMusicArtworkColors =
    staticCompositionLocalOf<MutableMap<String, Color>> { mutableMapOf() }

internal val LocalMusicGridColumns = staticCompositionLocalOf { 2 }

internal val LocalMusicTabletMode = staticCompositionLocalOf { false }

object MusicMetrics {
    val pageInset = 20.dp
    val toolbar = 44.dp
    val miniPlayer = 48.dp
    val navigation = 60.dp
    val cardRadius = 9.dp
    val footerSpace = 164.dp
}

@Composable
fun MusicText(
    text: String,
    size: Int = 17,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    align: TextAlign? = null,
    tabularNumbers: Boolean = false,
) {
    val foreground =
        if (color == Color.Unspecified) {
            if (LocalMusicDark.current) Color.White else Color.Black
        } else color
    val additive = LocalMusicPlayerPlus.current && foreground.alpha < 1f
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ranges =
        remember(text, additive) { if (additive) musicColorGlyphRanges(text) else emptyList() }
    val colorGlyphs =
        remember(text, layout, ranges) {
            layout
                ?.takeIf { it.layoutInput.text.text == text }
                ?.let { musicColorGlyphPath(it, ranges) }
        }
    val plusPaint = rememberMusicPlusPaint()
    val normalPaint = remember { Paint() }
    val blending =
        if (!additive) Modifier
        else
            Modifier.drawWithContent {
                drawMusicTextLayers(
                    Rect(0f, 0f, this.size.width, this.size.height),
                    plusPaint,
                    normalPaint,
                    colorGlyphs,
                    draw = { drawContent() },
                    additive = true,
                )
            }
    Text(
        text,
        modifier.then(blending),
        foreground,
        style =
            TextStyle(
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    fontSize = size.sp,
                    lineHeight = (size * 1.18f).sp,
                    letterSpacing = (-.25).sp,
                    fontWeight = weight,
                    textAlign = align ?: TextAlign.Start,
                )
                .copy(fontFeatureSettings = if (tabularNumbers) "tnum" else null)
                .musicTextStyle(),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (additive) layout = it },
    )
}

@Composable
fun referenceTopInset(): Dp =
    maxOf(54.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())

fun musicTime(ms: Long) =
    "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable
fun IconTouch(
    name: String,
    label: String,
    click: () -> Unit,
    color: Color = Color.Black,
    iconSize: Dp = 24.dp,
    touchSize: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(touchSize).clip(CircleShape).clickable(onClick = click).semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        MusicIcon(name, color, iconSize)
    }
}

@Composable
fun GlassIcon(
    name: String,
    label: String,
    click: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val color = if (LocalMusicDark.current) Color.White else Color.Black
    MusicGlass(modifier.size(size).semantics { contentDescription = label }, size / 2, click) {
        MusicIcon(name, color, if (size > 40.dp) 24.dp else 20.dp)
    }
}

@Composable
internal fun AlbumTile(
    model: BellStore,
    state: BellState,
    item: Item,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val offline = LocalMusicOffline.current
    val link =
        rememberArtworkLink(item.id) {
            model.accept(BellIntent.OpenItem(item, offlineOnly = offline))
        }
    Column(modifier.clickable(onClick = link.open)) {
        MusicArtwork(
            model,
            state,
            item,
            Modifier.fillMaxWidth().aspectRatio(1f),
            9.dp,
            sharedKey = link.key,
            shadowElevation = if (compact) 5.dp else 2.dp,
        )
        Spacer(Modifier.height(5.dp))
        MusicText(
            item.name,
            if (compact) 13 else 14,
            weight = FontWeight.Medium,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
            align = if (compact) TextAlign.Center else TextAlign.Start,
        )
        if (!compact) MusicText(item.artist, 13, Color(0xFF8A8A8E))
    }
}

@Composable
internal fun DetailList(
    title: String,
    back: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    MusicPage(title, content = content)
}

@Composable
internal fun CoverListRow(
    title: String,
    subtitle: String?,
    cover: @Composable () -> Unit,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(80.dp).clickable(onClick = click),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            MusicText(title, 17)
            subtitle?.let { MusicText(it, 13, Color.Gray) }
        }
        MusicIcon("chevron", Color(0xFFC5C5C7), 20.dp)
    }
    Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(.5.dp).background(Color(0xFFE4E4E6)))
}

@Composable
fun MusicTrackRow(
    model: BellStore,
    state: BellState,
    item: Item,
    index: Int,
    tracks: List<Item>,
    numbered: Boolean = false,
    subtitle: String? = null,
    numberLabel: Int? = null,
    fullWidthDivider: Boolean = false,
    swipeEnabled: Boolean = true,
) {
    val menuStrings = rememberMenuStrings()

    val dark = LocalMusicDark.current
    val primary = if (dark) Color.White else Color.Black
    val secondary = primary.copy(alpha = .5f)
    val share = rememberMusicSharer()
    val hasSubtitle = !numbered || !subtitle.isNullOrBlank()
    val row: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (numbered) 52.dp else 56.dp)
                .clickable { model.accept(BellIntent.Play(tracks, index)) }
                .padding(vertical = if (numbered && hasSubtitle) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (numbered)
                MusicText(
                    (numberLabel ?: item.number ?: index + 1).toString(),
                    17,
                    secondary,
                    modifier = Modifier.width(31.dp),
                )
            else {
                MusicArtwork(model, state, item, Modifier.size(48.dp), 5.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                MusicText(item.name, 17, primary)
                if (hasSubtitle)
                    MusicText(
                        subtitle ?: item.artist,
                        14,
                        secondary,
                        lines = if (numbered) 2 else 1,
                    )
            }
            MusicDownloadMarker(model, state, item, secondary)
            MusicMenu(
                songMenu(
                    menuStrings,
                    model,
                    state,
                    item,
                    share,
                    offlineOnly = LocalMusicOffline.current,
                ),
                Modifier.size(40.dp),
                MusicMenuStyle.Plain,
            )
        }
    }
    if (swipeEnabled) MusicSwipeRow(model, state, item, row) else row()
    Box(
        Modifier.padding(start = if (fullWidthDivider) 0.dp else if (numbered) 31.dp else 60.dp)
            .fillMaxWidth()
            .height(.5.dp)
            .musicSwipeDivider(beforeId = item.id, afterId = tracks.getOrNull(index + 1)?.id)
            .musicBackground(primary.copy(alpha = if (dark) .13f else .12f))
    )
}

@Composable
fun EmptyMusic(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MusicText(title, 20, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        MusicText(subtitle, 14, Color.Gray, lines = 3, align = TextAlign.Center)
    }
}
