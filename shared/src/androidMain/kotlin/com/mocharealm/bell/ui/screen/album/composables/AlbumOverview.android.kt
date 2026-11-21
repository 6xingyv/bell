// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album.composables

import android.graphics.Typeface
import android.text.Html
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.composables.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.musicTextStyle
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.*
import org.jetbrains.compose.resources.stringResource

internal actual fun musicOverviewText(value: String): String =
    Html.fromHtml(musicOverviewHtml(value), Html.FROM_HTML_MODE_LEGACY).toString().trim()

private fun overviewAnnotatedText(value: String): AnnotatedString {
    val html = Html.fromHtml(musicOverviewHtml(value), Html.FROM_HTML_MODE_LEGACY)
    val first = html.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val last = (html.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(first)
    return buildAnnotatedString {
        append(html.subSequence(first, last).toString())
        html.getSpans(0, html.length, Any::class.java).forEach { span ->
            val start = (html.getSpanStart(span) - first).coerceIn(0, length)
            val end = (html.getSpanEnd(span) - first).coerceIn(start, length)
            val style =
                when (span) {
                    is StyleSpan ->
                        SpanStyle(
                            fontWeight =
                                if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                            fontStyle =
                                if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null,
                        )
                    is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                    else -> null
                }
            if (style != null && end > start) addStyle(style, start, end)
        }
    }
}

@Composable
internal actual fun AlbumOverviewSheet(
    title: String,
    overview: String,
    visible: Boolean,
    dismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val palette = defaultGlassColors(dark, MusicAccent)
    val colors = palette.copy(groupedBackground = palette.elevatedBackground)
    val list = rememberLazyListState()
    val edge = rememberGlasmioHeaderScrollEdge(list)
    val text = remember(overview) { overviewAnnotatedText(overview) }
    val close = stringResource(Res.string.close)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalContentColor provides colors.content,
        LocalMusicDark provides dark,
        LocalMusicPlayerPlus provides false,
    ) {
        GlasmioBottomSheet(
            visible = visible,
            onDismissRequest = dismiss,
            title = title,
            backdrop = LocalBlurBackdrop.current,
            topBarBlurProgress = edge,
            contentBackground = {
                Box(Modifier.fillMaxSize().background(colors.elevatedBackground))
            },
            topBar = {
                GlasmioSheetTopToolbar(
                    title,
                    navigation = {
                        GlasmioSheetToolbarButton(onClick = dismiss) {
                            MusicIcon("xmark", colors.content, 22.dp, description = close)
                        }
                    },
                )
            },
        ) {
            RequireMusicMenuRecording()
            val padding = LocalGlasmioSheetContentPadding.current
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = padding.calculateTopPadding() + 20.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
            ) {
                item {
                    Text(
                        text,
                        style =
                            MaterialTheme.typography.bodyLarge
                                .copy(
                                    fontSize = 17.sp,
                                    lineHeight = 24.sp,
                                    letterSpacing = (-.25).sp,
                                    color = colors.content,
                                )
                                .musicTextStyle(),
                    )
                }
            }
        }
    }
}
