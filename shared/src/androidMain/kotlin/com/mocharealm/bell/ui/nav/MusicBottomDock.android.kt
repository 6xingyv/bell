// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.composables.IconTouch
import com.mocharealm.bell.ui.composables.LocalMusicNavigationCompact
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.ui.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/** Native navigation/search controls and mini player share one Glasmio contact/material host. */
@Composable
internal fun MusicBottomDock(
    tabs: List<GlasmioTabItem<String>>,
    selected: String,
    scrollCompact: State<Float>,
    landing: State<Float>,
    entryCompact: Float,
    searchPage: Boolean,
    searchEditing: Boolean,
    focusTransition: State<Float>,
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    onFocusSearch: () -> Unit,
    onSearchPage: () -> Unit,
    onReturnFromSearch: () -> Unit,
    onTab: (String) -> Unit,
    onExpand: () -> Unit,
    onVoice: () -> Unit,
    onSearchBounds: (Rect) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit,
) {
    val text_cancel = stringResource(Res.string.cancel)
    val text_voice_search = stringResource(Res.string.voice_search)
    val text_search_placeholder = stringResource(Res.string.search_placeholder)

    val currentPage = rememberUpdatedState(searchPage)
    val currentEntry = rememberUpdatedState(entryCompact)
    // Freeze the source scroll endpoint through both halves of the search morph.
    // The route changes before the spring finishes when returning from search.
    val sourceCompact =
        remember(scrollCompact, landing) {
            derivedStateOf {
                (if (currentPage.value || landing.value > 0f) currentEntry.value
                    else scrollCompact.value)
                    .coerceIn(0f, 1f)
            }
        }
    val mainCompact =
        remember(sourceCompact, landing) {
            derivedStateOf {
                sourceCompact.value + (1f - sourceCompact.value) * landing.value.coerceIn(0f, 1f)
            }
        }
    val miniCompact =
        remember(sourceCompact, landing) {
            derivedStateOf {
                sourceCompact.value * (1f - landing.value.coerceIn(0f, 1f))
            }
        }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val player = LocalPlayerMotion.current
    val miniActive =
        rememberUpdatedState(!searchEditing && (player == null || player.progress == 0f))
    val active = remember { { miniActive.value } }
    val miniProgress = miniCompact.value.coerceIn(0f, 1f)
    val browse = landing.value.coerceIn(0f, 1f)
    // Match the visible vertical gap to the horizontal 8dp gap. The
    // compact row has a 7dp inset inside its native 62dp allocation.
    val height =
        118.dp -
            56.dp * miniProgress -
            (NavigationBarSurfaceHeight - CompactBottomControlSize) / 2 * browse
    var contactSettled by remember { mutableStateOf(false) }
    LaunchedEffect(sourceCompact, landing, focusTransition) {
        snapshotFlow { Triple(sourceCompact.value, landing.value, focusTransition.value) }
            .collectLatest { (compact, search, focus) ->
                contactSettled = false
                if (
                    (compact == 0f || compact == 1f) &&
                        (search == 0f || search == 1f) &&
                        focus == 0f
                ) {
                    // First layout and compact/search transitions use native individual materials.
                    // Geometry may be registered a frame after the spring reaches its endpoint.
                    withFrameNanos {}
                    delay(80)
                    contactSettled = true
                }
            }
    }
    val contactCalculation =
        remember(sourceCompact, landing, focusTransition, player) {
            {
                val compact = sourceCompact.value
                val search = landing.value
                contactSettled &&
                    (compact == 0f || compact == 1f) &&
                    (search == 0f || search == 1f) &&
                    focusTransition.value == 0f &&
                    (player == null || player.progress == 0f)
            }
        }

    // Default compact controls have a native inset on both outer edges. Keep the
    // entire unfocused search row on those same visible edges, not just its circles.
    val searchOuterInset = (NavigationBarSurfaceHeight - CompactBottomControlSize) / 2 * browse
    BottomNavigationContact(
        backdrop = backdrop,
        calculateContact = contactCalculation,
        modifier =
            modifier.padding(horizontal = searchOuterInset).height(height).graphicsLayer {
                val p = focusTransition.value
                alpha = 1f - p
                scaleX = 1f - .08f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(if (ltr) 1f else 0f, 1f)
            },
    ) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                CompositionLocalProvider(
                    LocalBottomControlContactMemberActive provides active,
                    LocalMusicNavigationCompact provides miniProgress,
                ) {
                    miniPlayer()
                }
                GlasmioNavigationBar(
                    tabs,
                    selected,
                    onTab,
                    {
                        if (searchPage) onReturnFromSearch() else onExpand()
                    },
                    mainCompact,
                    CompactBottomControlSize,
                    backdrop = backdrop,
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val controlSize =
                        NavigationBarSurfaceHeight -
                            (NavigationBarSurfaceHeight - CompactBottomControlSize) *
                                mainCompact.value
                    val scale = controlSize / 48.dp
                    val slotInset =
                        ((NavigationBarSurfaceHeight - CompactBottomControlSize) / 2 *
                                mainCompact.value *
                                (1f - browse))
                            .coerceAtLeast(0.dp)
                    // One native field owns both endpoints. Replacing it with a button at
                    // progress zero discarded the material/press state on every close.
                    BoxWithConstraints(
                        Modifier.fillMaxSize().padding(horizontal = slotInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlasmioSearchToolbar(
                            query,
                            onQuery,
                            {},
                            {},
                            text_cancel,
                            text_search_placeholder,
                            autoFocus = false,
                            morphProgress = browse,
                            enabled = searchPage && !searchEditing && browse > .99f,
                            showCancel = false,
                            onFieldFocusChanged = { if (it) onFocusSearch() },
                            onSurfaceClick = {
                                if (!searchPage) onSearchPage()
                                else if (!searchEditing) onFocusSearch()
                            },
                            collapsedContent = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    SfIcon(
                                        SfSymbol.Search,
                                        null,
                                        tint = LocalGlassColors.current.content,
                                        size = CompactBottomControlIconSize,
                                        fontSize = 18.sp,
                                        weight = FontWeight.Medium,
                                        fontFeatureSettings = "'ss16' 1",
                                        fitGlyphToBounds = false,
                                    )
                                }
                            },
                            trailingContent = {
                                if (browse > .5f)
                                    Box(
                                        Modifier.graphicsLayer {
                                            alpha = ((browse - .5f) / .5f).coerceIn(0f, 1f)
                                        }
                                    ) {
                                        IconTouch(
                                            "mic",
                                            text_voice_search,
                                            { if (searchPage && browse > .99f) onVoice() },
                                            iconSize = 20.dp,
                                            touchSize = 32.dp,
                                        )
                                    }
                            },
                            modifier =
                                Modifier.width(maxWidth / scale)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .onGloballyPositioned { onSearchBounds(it.boundsInWindow()) },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val rowHeight = NavigationBarSurfaceHeight.roundToPx()
            val gap = 8.dp.roundToPx()
            val fullNavigationWidth = (width - rowHeight - gap).coerceAtLeast(1)
            val compactLeading = CompactBottomControlSize.roundToPx()
            val trailingWidth =
                (rowHeight + ((width - compactLeading - gap) - rowHeight) * browse)
                    .roundToInt()
                    .coerceAtLeast(1)
            // At rest each 48pt endpoint sits inside a 62pt native control slot.
            val inset =
                ((NavigationBarSurfaceHeight + CompactBottomControlSize) / 2 + 8.dp).roundToPx()
            val miniInset = (inset * miniProgress).roundToInt()
            val mini =
                measurables[0].measure(
                    Constraints.fixed((width - miniInset * 2).coerceAtLeast(1), 48.dp.roundToPx())
                )
            val navigation =
                measurables[1].measure(Constraints.fixed(fullNavigationWidth, rowHeight))
            val trailing = measurables[2].measure(Constraints.fixed(trailingWidth, rowHeight))
            layout(width, constraints.maxHeight) {
                mini.place(miniInset, (7.dp.toPx() * miniProgress).roundToInt())
                // Glasmio adds a 7dp compact inset inside its 62dp host. Remove
                // that inset here so the visible 48dp leading circle and the field
                // share the same outer gutter and retain their 8dp gap.
                val leadingInset =
                    (((NavigationBarSurfaceHeight - CompactBottomControlSize) / 2).toPx() *
                            mainCompact.value *
                            browse)
                        .roundToInt()
                navigation.place(
                    if (ltr) -leadingInset else width - fullNavigationWidth + leadingInset,
                    constraints.maxHeight - rowHeight,
                )
                trailing.place(
                    if (ltr) width - trailingWidth else 0,
                    constraints.maxHeight - rowHeight,
                )
            }
        }
    }
}
