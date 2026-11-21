// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.utils.withPhonetics
import com.mocharealm.accompanist.lyrics.ui.composable.list.rememberLyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsAnchor
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsFade
import com.mocharealm.bell.domain.lyrics.DisplayLyrics
import com.mocharealm.bell.domain.lyrics.display
import com.mocharealm.bell.domain.lyrics.translationTracks
import com.mocharealm.bell.domain.model.LyricKind
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.AmbientArtwork
import com.mocharealm.bell.ui.composables.IconTouch
import com.mocharealm.bell.ui.composables.ListeningAvatar
import com.mocharealm.bell.ui.composables.ListeningGroupsSheet
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicIcon
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuAction
import com.mocharealm.bell.ui.composables.MusicMenuSource
import com.mocharealm.bell.ui.composables.MusicMenuStyle
import com.mocharealm.bell.ui.composables.MusicOutputMenu
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.playerArtistMenu
import com.mocharealm.bell.ui.composables.rememberDeviceVolume
import com.mocharealm.bell.ui.composables.rememberMusicSharer
import com.mocharealm.bell.ui.composables.songMenu
import com.mocharealm.bell.ui.nav.LocalPlayerMotion
import com.mocharealm.bell.ui.screen.player.composables.MusicPlayerMetadata
import com.mocharealm.bell.ui.screen.player.composables.MusicPlayerQueue
import com.mocharealm.bell.ui.screen.player.composables.MusicPlayerTimeline
import com.mocharealm.bell.ui.screen.player.composables.MusicProgress
import com.mocharealm.bell.ui.screen.player.composables.MusicStaticLyricText
import com.mocharealm.bell.ui.screen.player.composables.PlayerBottomIcon
import com.mocharealm.bell.ui.screen.player.composables.PlayerRoundIcon
import com.mocharealm.bell.ui.screen.player.composables.QueueCurrentHeaderKey
import com.mocharealm.bell.ui.screen.player.composables.QueueDragOverlay
import com.mocharealm.bell.ui.screen.player.composables.QueueDragState
import com.mocharealm.bell.ui.screen.player.composables.QueueGlassHost
import com.mocharealm.bell.ui.screen.player.composables.queueCurrentIndex
import com.mocharealm.bell.ui.screen.player.composables.queueHistoryHeaderOffset
import com.mocharealm.bell.ui.screen.player.composables.rememberMusicLyricsTypography
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.formatBellString
import com.mocharealm.bell.ui.utils.isMusicPhoneticCaption
import com.mocharealm.bell.ui.utils.musicBackground
import com.mocharealm.bell.ui.utils.musicPhoneticsForDisplay
import com.mocharealm.bell.ui.utils.musicPlus
import com.mocharealm.bell.ui.utils.needsMusicPhonetics
import com.mocharealm.bell.ui.utils.rememberMenuStrings
import com.mocharealm.bell.ui.utils.rememberMusicPhoneticProvider
import com.mocharealm.bell.ui.utils.rememberPlaybackPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreen(
    model: BellStore,
    state: BellState,
    dismiss: () -> Unit,
    viewModel: PlayerViewModel,
    dragModifier: Modifier = Modifier,
) {
    val menuStrings = rememberMenuStrings()

    val text_favorite_songs = stringResource(Res.string.favorite_songs)
    val text_lyrics = stringResource(Res.string.lyrics)
    val text_volume = stringResource(Res.string.volume)
    val text_show_translation = stringResource(Res.string.show_translation)
    val text_hide_pronunciation = stringResource(Res.string.hide_pronunciation)
    val text_translation_and_pronunciation =
        stringResource(Res.string.translation_and_pronunciation)
    val text_audio_output = stringResource(Res.string.audio_output)
    val text_hide_translation = stringResource(Res.string.hide_translation)
    val text_no_lyrics = stringResource(Res.string.no_lyrics)
    val text_collapse_player = stringResource(Res.string.collapse_player)
    val text_show_pronunciation = stringResource(Res.string.show_pronunciation)
    val text_playback_queue = stringResource(Res.string.playback_queue)
    val text_translation_language = stringResource(Res.string.translation_language)
    val text_next_track = stringResource(Res.string.next_track)
    val text_pause = stringResource(Res.string.pause)
    val text_play = stringResource(Res.string.play)
    val text_previous_track = stringResource(Res.string.previous_track)

    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    val item = screenState.current ?: return
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val windowHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val tabletLandscape = windowWidth >= 600.dp && windowWidth > windowHeight
    val presentation by viewModel.presentation.collectAsState()
    val panel = presentation.panel
    val controlsVisible = presentation.controlsVisible
    var listeningSheet by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    val motion = LocalPlayerMotion.current
    val chromeAlpha = (((motion?.progress ?: 1f) - .18f) / .32f).coerceIn(0f, 1f)
    val lyricsList = rememberLyricsLazyListState()
    val textLyricsList = rememberLazyListState()
    val queueList =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                queueCurrentIndex(screenState.history.size) + if (tabletLandscape) 1 else 0
        )
    val queueScope = rememberCoroutineScope()
    val queueDrag = remember(queueList, queueScope) { QueueDragState(queueList, queueScope) }
    val latestPanel by rememberUpdatedState(panel)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    fun revealControls() {
        viewModel.accept(PlayerIntent.RevealControls)
        interaction++
    }
    fun selectPanel(next: PlayerPanel) {
        viewModel.accept(PlayerIntent.SelectPanel(next))
        revealControls()
    }
    LaunchedEffect(Unit) { revealControls() }
    LaunchedEffect(panel, item.id) {
        revealControls()
        if (panel == PlayerPanel.Queue) {
            queueList.scrollToItem(
                queueCurrentIndex(screenState.history.size) + if (tabletLandscape) 1 else 0
            )
        }
    }
    LaunchedEffect(
        panel,
        interaction,
        touching,
        screenState.audio.paused,
        motion?.dragging,
        motion?.expanded,
        screenState.lyrics,
    ) {
        if (
            !tabletLandscape &&
                panel == PlayerPanel.Lyrics &&
                !touching &&
                !screenState.audio.paused &&
                motion?.dragging != true &&
                motion?.expanded != false &&
                screenState.lyrics.tracks.isNotEmpty()
        ) {
            delay(3500)
            viewModel.accept(PlayerIntent.HideControls)
        }
    }
    val hidden by
        animateFloatAsState(
            if (
                !tabletLandscape &&
                    ((panel == PlayerPanel.Lyrics && !controlsVisible) ||
                        (panel == PlayerPanel.Queue && presentation.queueDragging))
            )
                1f
            else 0f,
            tween(300, easing = FastOutSlowInEasing),
            label = "lyrics controls",
        )
    var language by remember(item.id) { mutableStateOf<String?>(null) }
    var showTranslation by rememberSaveable(item.id) { mutableStateOf(true) }
    var showPronunciation by rememberSaveable(item.id) { mutableStateOf(true) }
    val phoneticProvider = rememberMusicPhoneticProvider()
    // Decode/enrich once off the UI thread. Visibility is owned by Lyrics UI, not the document.
    val lyric by
        produceState<DisplayLyrics?>(
            null,
            screenState.lyrics,
            item.id,
            language,
            phoneticProvider,
        ) {
            value = null
            value =
                withContext(Dispatchers.Default) {
                    val display = screenState.lyrics.display(item.durationMs, language)
                    val timed =
                        display.timed.withPhonetics(phoneticProvider).musicPhoneticsForDisplay()
                    val phonetics =
                        display.lines.mapIndexed { index, row ->
                            if (!row.first.needsMusicPhonetics()) null
                            else
                                display.phonetics.getOrNull(index)?.takeIf {
                                    it.isMusicPhoneticCaption()
                                }
                                    ?: if (display.kind == LyricKind.Unsynced)
                                        phoneticProvider.getPhonetic(row.first).takeIf {
                                            it.isMusicPhoneticCaption()
                                        }
                                    else null
                        }
                    display.copy(timed = timed, phonetics = phonetics)
                }
        }
    val lyricsTypography = rememberMusicLyricsTypography()
    val position = rememberPlaybackPosition(model.playback)
    val deviceVolume = rememberDeviceVolume()
    var volume by remember { mutableFloatStateOf(deviceVolume.current) }
    var seek by remember(item.id) { mutableStateOf<Float?>(null) }
    val duration =
        (screenState.audio.durationMs.takeIf { it > 0 } ?: item.durationMs).coerceAtLeast(1)
    val share = rememberMusicSharer()
    val menu =
        songMenu(
            menuStrings,
            model,
            state,
            item,
            share,
            lyric?.lines.orEmpty().joinToString("\n") { (text, translation) ->
                listOfNotNull(text, translation).joinToString("\n")
            },
            dismiss,
        )
    val translationTracks =
        remember(screenState.lyrics) {
            screenState.lyrics.translationTracks().filter {
                it.lines.any { line -> line.text.isNotBlank() }
            }
        }
    val hasPronunciation =
        remember(lyric) {
            lyric?.let { display ->
                display.phonetics.any { !it.isNullOrBlank() } ||
                    display.timed.lines.any { line ->
                        when (line) {
                            is KaraokeLine ->
                                !line.phonetic.isNullOrBlank() ||
                                    line.syllables.any { !it.phonetic.isNullOrBlank() } ||
                                    (line as? KaraokeLine.MainKaraokeLine)
                                        ?.accompanimentLines
                                        .orEmpty()
                                        .any {
                                            !it.phonetic.isNullOrBlank() ||
                                                it.syllables.any { s ->
                                                    !s.phonetic.isNullOrBlank()
                                                }
                                        }
                            is SyncedLine -> !line.phonetic.isNullOrBlank()
                            else -> false
                        }
                    }
            } == true
        }
    val languageMenu = buildList {
        add(
            MusicMenuAction(
                if (showPronunciation && hasPronunciation) text_hide_pronunciation
                else text_show_pronunciation,
                "pronunciation",
                enabled = hasPronunciation,
            ) {
                showPronunciation = !showPronunciation
            }
        )
        add(
            MusicMenuAction(
                if (showTranslation && translationTracks.isNotEmpty()) text_hide_translation
                else text_show_translation,
                "translation",
                enabled = translationTracks.isNotEmpty(),
            ) {
                showTranslation = !showTranslation
            }
        )
        if (translationTracks.size > 1)
            translationTracks.forEachIndexed { index, track ->
                add(
                    MusicMenuAction(
                        track.language ?: formatBellString(text_translation_language, index + 1),
                        if (language == (track.language ?: "") || language == null && index == 0)
                            "check"
                        else "translation",
                        separatorBefore = index == 0,
                    ) {
                        language = track.language ?: ""
                        showTranslation = true
                    }
                )
            }
    }
    CompositionLocalProvider(LocalMusicDark provides true, LocalMusicPlayerPlus provides true) {
        MusicMenuSource {
            ListeningGroupsSheet(model, state, visible = listeningSheet) { listeningSheet = false }
            BoxWithConstraints(
                Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
                    awaitEachGesture {
                        val first =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        touching = true
                        interaction++
                        val lyricsHidden =
                            latestPanel == PlayerPanel.Lyrics && !latestControlsVisible
                        val lyricsStart = size.height * .108f + 72.dp.toPx()
                        val watchPull = lyricsHidden && first.position.y >= lyricsStart
                        var intercept = lyricsHidden && first.position.y >= size.height * .5f
                        if (intercept) {
                            first.consume()
                            revealControls()
                        }
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == first.id }
                                if (!intercept && watchPull && pointer != null) {
                                    val delta = pointer.position - first.position
                                    if (
                                        delta.y > viewConfiguration.touchSlop &&
                                            delta.y > kotlin.math.abs(delta.x)
                                    ) {
                                        intercept = true
                                        revealControls()
                                    }
                                }
                                // The wake-up gesture belongs to the player, including its UP.
                                // A newly revealed slider/button must not act on that same touch.
                                if (intercept) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touching = false
                        }
                    }
                }
            ) {
                val width = maxWidth
                val height = maxHeight
                val tabletLandscape = width >= 600.dp && width > height
                val splitPlayerPanel =
                    tabletLandscape && (panel == PlayerPanel.Lyrics || panel == PlayerPanel.Queue)
                val leftPaneStart = width * .04f
                val leftPaneWidth = width * .42f
                val tabletCenteredPaneStart = (width - leftPaneWidth) / 2
                val lyricsPaneStart = width * .48f
                val lyricsPaneWidth = (width - lyricsPaneStart - 24.dp).coerceAtLeast(1.dp)
                val tabletPaneStart by
                    animateDpAsState(
                        if (splitPlayerPanel) leftPaneStart else tabletCenteredPaneStart,
                        spring(dampingRatio = 1f, stiffness = 420f),
                        label = "tablet player pane",
                    )
                val compact by
                    animateFloatAsState(
                        if (panel == PlayerPanel.Cover || splitPlayerPanel) 0f else 1f,
                        spring(dampingRatio = 1f, stiffness = 420f),
                        label = "player header",
                    )
                QueueGlassHost(
                    queueDrag.key != null,
                    Modifier.fillMaxSize(),
                    content = {
                        Box(Modifier.fillMaxSize()) {
                            AmbientArtwork(
                                model,
                                state,
                                item,
                                player = true,
                                modifier =
                                    Modifier.graphicsLayer { alpha = motion?.progress ?: 1f },
                            )
                            val coverTop = height * .108f
                            val largeSide =
                                minOf(
                                    (if (tabletLandscape) leftPaneWidth - 48.dp else width - 48.dp)
                                        .coerceAtLeast(1.dp),
                                    height * .405f,
                                )
                            val smallSide = 72.dp
                            val coverSide = largeSide + (smallSide - largeSide) * compact
                            val metadataHeight =
                                if (state.listening.active) 78.dp - 6.dp * compact
                                else 52.dp + 20.dp * compact
                            val expandedCoverX =
                                if (tabletLandscape)
                                    tabletPaneStart + (leftPaneWidth - largeSide) / 2
                                else (width - largeSide) / 2
                            val coverX = expandedCoverX + (32.dp - expandedCoverX) * compact
                            val titleX =
                                if (tabletLandscape) tabletPaneStart + 32.dp
                                else 32.dp + (smallSide + 12.dp) * compact
                            val standardTitleY =
                                height * .565f + (coverTop + 8.dp - height * .565f) * compact
                            val titleY =
                                if (tabletLandscape && panel == PlayerPanel.Lyrics)
                                    maxOf(height * .565f, coverTop + largeSide + 24.dp)
                                else standardTitleY
                            val timelineTop = if (tabletLandscape) height * .65f else height * .64f
                            val transportTop =
                                if (tabletLandscape) maxOf(height * .75f, timelineTop + 52.dp)
                                else height * .73f
                            val panelTop = coverTop + smallSide
                            val lyricsTop = if (splitPlayerPanel) coverTop else panelTop
                            val panelPaneStart = lyricsPaneStart
                            val panelPaneWidth = lyricsPaneWidth
                            val lyricsX = if (splitPlayerPanel) panelPaneStart else 0.dp
                            val lyricsWidth = if (splitPlayerPanel) panelPaneWidth else width
                            val controlX = if (tabletLandscape) tabletPaneStart else 0.dp
                            val controlWidth = if (tabletLandscape) leftPaneWidth else width
                            val bottomInset =
                                WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                            // The lyric viewport touches both the metadata and the progress track.
                            // Translation controls overlay it; they do not consume lyric height.
                            val panelBottom by
                                animateDpAsState(
                                    timelineTop + 8.5.dp,
                                    spring(dampingRatio = 1f, stiffness = 420f),
                                    label = "player content bottom",
                                )
                            val lyricsBottom =
                                panelBottom + (height - bottomInset - 16.dp - panelBottom) * hidden
                            val lyricsViewportBottom =
                                if (splitPlayerPanel) height - bottomInset - 16.dp else lyricsBottom

                            // Keep the handle on top of the sheet, including in lyrics and queue
                            // modes.
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(coverTop)
                                    .musicPlus(alpha = chromeAlpha)
                                    .then(dragModifier)
                                    .semantics { contentDescription = text_collapse_player }
                                    .clickable(onClick = dismiss),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.offset(y = height * .076f - coverTop / 2)
                                        .size(60.dp, 5.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = .4f))
                                )
                            }
                            Crossfade(
                                panel,
                                animationSpec = tween(220),
                                modifier =
                                    Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha },
                            ) { mode ->
                                when (mode) {
                                    PlayerPanel.Cover -> Unit
                                    PlayerPanel.Lyrics ->
                                        Box(
                                            Modifier.offset(x = lyricsX, y = lyricsTop)
                                                .width(lyricsWidth)
                                                .height(
                                                    (lyricsViewportBottom - lyricsTop)
                                                        .coerceAtLeast(1.dp)
                                                )
                                                .padding(horizontal = 16.dp)
                                                .pointerInput(Unit) {
                                                    detectTapGestures {
                                                        viewModel.accept(
                                                            PlayerIntent.ToggleControls
                                                        )
                                                        interaction++
                                                    }
                                                }
                                        ) {
                                            val lyric = lyric
                                            when {
                                                lyric == null -> Unit
                                                lyric.lines.isEmpty() ->
                                                    Box(
                                                        Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        MusicText(
                                                            text_no_lyrics,
                                                            20,
                                                            Color.White.copy(alpha = .55f),
                                                        )
                                                    }
                                                lyric.kind == LyricKind.Unsynced ->
                                                    LazyColumn(
                                                        Modifier.fillMaxSize()
                                                            .padding(horizontal = 16.dp),
                                                        state = textLyricsList,
                                                        verticalArrangement =
                                                            Arrangement.spacedBy(16.dp),
                                                    ) {
                                                        itemsIndexed(lyric.lines) { index, row ->
                                                            val (original, translation) = row
                                                            Column {
                                                                MusicStaticLyricText(
                                                                    original,
                                                                    if (
                                                                        index in
                                                                            lyric.backgroundIndices
                                                                    )
                                                                        lyricsTypography
                                                                            .accompaniment
                                                                    else lyricsTypography.normal,
                                                                )
                                                                lyric.phonetics
                                                                    .getOrNull(index)
                                                                    ?.takeIf { showPronunciation }
                                                                    ?.let {
                                                                        Spacer(
                                                                            Modifier.height(6.dp)
                                                                        )
                                                                        MusicStaticLyricText(
                                                                            it,
                                                                            lyricsTypography
                                                                                .phonetic,
                                                                            .7f,
                                                                        )
                                                                    }
                                                                translation
                                                                    ?.takeIf { showTranslation }
                                                                    ?.let {
                                                                        Spacer(
                                                                            Modifier.height(8.dp)
                                                                        )
                                                                        MusicStaticLyricText(
                                                                            it,
                                                                            lyricsTypography
                                                                                .translation,
                                                                            .6f,
                                                                        )
                                                                    }
                                                            }
                                                        }
                                                    }
                                                else ->
                                                    KaraokeLyricsView(
                                                        lyricsList,
                                                        lyric.timed,
                                                        currentPosition = { position.intValue },
                                                        onLineClicked = {
                                                            viewModel.accept(
                                                                PlayerIntent.Seek(it.start.toLong())
                                                            )
                                                            revealControls()
                                                        },
                                                        onLinePressed = { revealControls() },
                                                        modifier =
                                                            Modifier.fillMaxSize().musicPlus(),
                                                        anchor =
                                                            if (tabletLandscape)
                                                                LyricsAnchor.Fraction(.25f)
                                                            else LyricsAnchor.Fixed(64.dp),
                                                        bottomFade =
                                                            if (tabletLandscape)
                                                                LyricsFade.Fraction(.2f)
                                                            else LyricsFade.Fraction(.5f),
                                                        textColor = Color.White,
                                                        blendMode = BlendMode.SrcOver,
                                                        showTranslation = showTranslation,
                                                        showPhonetic = showPronunciation,
                                                        normalLineTextStyle =
                                                            lyricsTypography.normal,
                                                        translationTextStyle =
                                                            lyricsTypography.translation,
                                                        phoneticTextStyle =
                                                            lyricsTypography.phonetic,
                                                        accompanimentLineTextStyle =
                                                            lyricsTypography.accompaniment,
                                                    )
                                            }
                                        }
                                    PlayerPanel.Queue ->
                                        MusicPlayerQueue(
                                            model,
                                            state,
                                            viewModel,
                                            queueList,
                                            Modifier.offset(
                                                    x =
                                                        if (splitPlayerPanel) panelPaneStart
                                                        else 0.dp,
                                                    y = coverTop,
                                                )
                                                .width(
                                                    if (splitPlayerPanel) panelPaneWidth else width
                                                )
                                                .height(
                                                    ((if (splitPlayerPanel) lyricsViewportBottom
                                                        else lyricsBottom) - coverTop)
                                                        .coerceAtLeast(1.dp)
                                                ),
                                            collapsedHeight = panelBottom - coverTop,
                                            drag = queueDrag,
                                            tabletLayout = splitPlayerPanel,
                                        )
                                }
                            }
                            // Cover, title and menu share one header through all three modes.
                            val followsQueueScroll = panel == PlayerPanel.Queue && !tabletLandscape
                            val headerInfo =
                                if (followsQueueScroll)
                                    queueList.layoutInfo.visibleItemsInfo.firstOrNull {
                                        it.key == QueueCurrentHeaderKey
                                    }
                                else null
                            val density = LocalDensity.current
                            val headerOffset =
                                if (followsQueueScroll)
                                    with(density) {
                                        (headerInfo?.offset
                                                ?: if (queueList.layoutInfo.totalItemsCount == 0) 0
                                                else -10000)
                                            .toDp()
                                    }
                                else 0.dp
                            Box(
                                Modifier.fillMaxSize().drawWithContent {
                                    if (followsQueueScroll)
                                        clipRect(
                                            top =
                                                (coverTop +
                                                        with(density) {
                                                            (queueHistoryHeaderOffset(
                                                                    queueList,
                                                                    screenState.history.size,
                                                                    40.dp.toPx(),
                                                                ) + 40.dp.toPx())
                                                                .toDp()
                                                        })
                                                    .toPx(),
                                            bottom = lyricsBottom.toPx(),
                                        ) {
                                            this@drawWithContent.drawContent()
                                        }
                                    else drawContent()
                                }
                            ) {
                                Box(Modifier.fillMaxSize().offset(y = headerOffset)) {
                                    MusicArtwork(
                                        model,
                                        state,
                                        item,
                                        Modifier.offset(x = coverX, y = coverTop)
                                            .size(coverSide)
                                            .then(
                                                if (panel == PlayerPanel.Cover) dragModifier
                                                else
                                                    Modifier.clickable {
                                                        selectPanel(PlayerPanel.Cover)
                                                    }
                                            ),
                                        9.dp,
                                        sharedKey = "playing:${item.id}",
                                        shadowElevation = 24.dp * (1f - compact),
                                    )
                                    Row(
                                        Modifier.offset(
                                                x = titleX,
                                                y =
                                                    titleY - (metadataHeight - 52.dp) / 2 +
                                                        2.dp * compact,
                                            )
                                            .width(
                                                if (tabletLandscape) leftPaneWidth - 64.dp
                                                else width - titleX - 32.dp
                                            )
                                            .height(metadataHeight)
                                            .graphicsLayer {
                                                alpha = chromeAlpha
                                                outsets = LayerOutsets(8.dp, 4.dp, 8.dp, 4.dp)
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            Modifier.weight(1f),
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            state.listening.entries
                                                .getOrNull(screenState.queueIndex)
                                                ?.takeIf {
                                                    state.listening.active && it.userName != null
                                                }
                                                ?.let { entry ->
                                                    Row(
                                                        Modifier.clip(CircleShape)
                                                            .musicBackground(
                                                                Color.White.copy(alpha = .12f)
                                                            )
                                                            .padding(4.dp),
                                                        verticalAlignment =
                                                            Alignment.CenterVertically,
                                                    ) {
                                                        ListeningAvatar(
                                                            model,
                                                            state,
                                                            entry.userId,
                                                            entry.userName,
                                                            16.dp,
                                                        )
                                                        Spacer(Modifier.width(5.dp))
                                                        MusicText(
                                                            entry.userName.orEmpty(),
                                                            11,
                                                            Color.White,
                                                            modifier = Modifier.padding(end = 5.dp),
                                                        )
                                                    }
                                                }
                                            MusicPlayerMetadata(
                                                item.name,
                                                item.artist,
                                                Modifier.fillMaxWidth(),
                                                playerArtistMenu(
                                                    menuStrings,
                                                    model,
                                                    state,
                                                    item,
                                                    dismiss,
                                                ),
                                                expanded = 1f - compact,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        PlayerRoundIcon(
                                            if (item.userData.favorite) "star.fill" else "star",
                                            text_favorite_songs,
                                            {
                                                model.accept(BellIntent.Favorite(item))
                                                revealControls()
                                            },
                                            active = item.userData.favorite,
                                        )
                                        Spacer(Modifier.width(16.dp - 8.dp * compact))
                                        MusicMenu(menu, Modifier.size(32.dp), MusicMenuStyle.Player)
                                    }
                                }
                            }
                            // This is a true overlay on the lyric viewport, with its own hit
                            // bounds.
                            // Keeping it outside the controls' offscreen Plus layer prevents
                            // clipping.
                            if (
                                hidden < .999f &&
                                    panel == PlayerPanel.Lyrics &&
                                    (translationTracks.isNotEmpty() || hasPronunciation)
                            )
                                Box(
                                    Modifier.offset(
                                            x =
                                                if (tabletLandscape)
                                                    lyricsPaneStart + lyricsPaneWidth - 56.dp
                                                else 32.dp,
                                            y =
                                                if (tabletLandscape) lyricsTop + 8.dp
                                                else
                                                    timelineTop - 60.dp +
                                                        (height - timelineTop + 24.dp) * hidden,
                                        )
                                        .size(40.dp)
                                        .graphicsLayer { alpha = (1f - hidden) * chromeAlpha }
                                ) {
                                    MusicMenu(
                                        languageMenu,
                                        Modifier.size(40.dp),
                                        MusicMenuStyle.Player,
                                        anchorIcon = "translation",
                                        anchorLabel = text_translation_and_pronunciation,
                                        anchorActive =
                                            (showTranslation && translationTracks.isNotEmpty()) ||
                                                (showPronunciation && hasPronunciation),
                                    )
                                }
                            // Queue controls fade in place; lyrics can slide them below the
                            // expanded viewport.
                            // Once hidden, remove their hit targets so the expanded lyrics receive
                            // input.
                            if (hidden < .999f)
                                Box(
                                    Modifier.offset(
                                            x = controlX,
                                            y =
                                                timelineTop +
                                                    if (panel == PlayerPanel.Queue) 0.dp
                                                    else (height - timelineTop + 24.dp) * hidden,
                                        )
                                        .width(controlWidth)
                                        .height(height - timelineTop)
                                        .then(dragModifier)
                                        .musicPlus(alpha = (1f - hidden) * chromeAlpha)
                                ) {
                                    MusicPlayerTimeline(
                                        position,
                                        duration,
                                        screenState.buffering,
                                        seek,
                                        { seek = it },
                                        {
                                            seek?.let {
                                                viewModel.accept(
                                                    PlayerIntent.Seek((it * duration).toLong())
                                                )
                                            }
                                            seek = null
                                            revealControls()
                                        },
                                    )
                                    Row(
                                        Modifier.offset(y = transportTop - timelineTop)
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .padding(horizontal = 42.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        IconTouch(
                                            "backward",
                                            text_previous_track,
                                            {
                                                viewModel.accept(PlayerIntent.Previous)
                                                revealControls()
                                            },
                                            Color.White,
                                            40.dp,
                                            64.dp,
                                        )
                                        IconTouch(
                                            if (screenState.audio.paused) "play" else "pause",
                                            if (screenState.audio.paused) text_play else text_pause,
                                            {
                                                viewModel.accept(PlayerIntent.Toggle)
                                                revealControls()
                                            },
                                            Color.White,
                                            48.dp,
                                            72.dp,
                                        )
                                        IconTouch(
                                            "forward",
                                            text_next_track,
                                            {
                                                viewModel.accept(PlayerIntent.Next())
                                                revealControls()
                                            },
                                            Color.White,
                                            40.dp,
                                            64.dp,
                                        )
                                    }
                                    Row(
                                        Modifier.offset(y = height * .855f - timelineTop)
                                            .fillMaxWidth()
                                            .height(24.dp)
                                            .padding(horizontal = 32.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MusicIcon("speaker", Color.White.copy(alpha = .45f), 14.dp)
                                        MusicProgress(
                                            volume,
                                            {
                                                volume = it
                                                deviceVolume.set(it)
                                            },
                                            { revealControls() },
                                            Modifier.weight(1f),
                                            color = Color.White.copy(alpha = .6f),
                                            label = text_volume,
                                        )
                                        MusicIcon("volume", Color.White.copy(alpha = .45f), 20.dp)
                                    }
                                    if (!tabletLandscape)
                                        Row(
                                            Modifier.offset(y = height * .9f - timelineTop)
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .padding(horizontal = 60.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            PlayerBottomIcon(
                                                if (panel == PlayerPanel.Lyrics) "lyrics.fill"
                                                else "lyrics",
                                                text_lyrics,
                                                panel == PlayerPanel.Lyrics,
                                            ) {
                                                selectPanel(PlayerPanel.Lyrics)
                                            }
                                            MusicOutputMenu(model, state, Modifier.size(40.dp)) {
                                                listeningSheet = true
                                            }
                                            PlayerBottomIcon(
                                                "queue",
                                                text_playback_queue,
                                                panel == PlayerPanel.Queue,
                                            ) {
                                                selectPanel(PlayerPanel.Queue)
                                            }
                                        }
                                }
                            if (tabletLandscape && hidden < .999f) {
                                val rightActionsWidth = 40.dp * 2 + 12.dp
                                Box(
                                    Modifier.offset(
                                            x = 32.dp,
                                            y = height - bottomInset - 56.dp,
                                        )
                                        .size(40.dp)
                                        .musicPlus(alpha = (1f - hidden) * chromeAlpha)
                                ) {
                                    MusicOutputMenu(
                                        model,
                                        state,
                                        Modifier.size(40.dp),
                                        centerHorizontally = false,
                                    ) {
                                        listeningSheet = true
                                    }
                                }
                                Row(
                                    Modifier.offset(
                                            x = width - 32.dp - rightActionsWidth,
                                            y = height - bottomInset - 56.dp,
                                        )
                                        .width(rightActionsWidth)
                                        .height(44.dp)
                                        .musicPlus(alpha = (1f - hidden) * chromeAlpha),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PlayerBottomIcon(
                                        if (panel == PlayerPanel.Lyrics) "lyrics.fill"
                                        else "lyrics",
                                        text_lyrics,
                                        panel == PlayerPanel.Lyrics,
                                    ) {
                                        selectPanel(PlayerPanel.Lyrics)
                                    }
                                    PlayerBottomIcon(
                                        "queue",
                                        text_playback_queue,
                                        panel == PlayerPanel.Queue,
                                    ) {
                                        selectPanel(PlayerPanel.Queue)
                                    }
                                }
                            }
                        }
                    },
                    overlay = {
                        QueueDragOverlay(
                            model,
                            state,
                            queueDrag,
                            height * .108f,
                            if (splitPlayerPanel && panel == PlayerPanel.Queue) lyricsPaneStart
                            else 0.dp,
                            if (splitPlayerPanel && panel == PlayerPanel.Queue) lyricsPaneWidth
                            else width,
                        )
                    },
                )
            }
        }
    }
}
// Position reads live in this restart scope, so the karaoke clock never recomposes the
// entire player, scans caption tracks or rebuilds menu actions every display frame.
