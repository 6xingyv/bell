// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.IconTouch
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.LocalMusicNavigationCompact
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicArtwork
import com.mocharealm.bell.ui.composables.MusicGlass
import com.mocharealm.bell.ui.composables.MusicMenu
import com.mocharealm.bell.ui.composables.MusicMenuStyle
import com.mocharealm.bell.ui.composables.rememberMusicSharer
import com.mocharealm.bell.ui.composables.songMenu
import com.mocharealm.bell.ui.nav.LocalPlayerMotion
import com.mocharealm.bell.ui.screen.player.PlayerPanel
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import com.mocharealm.bell.ui.utils.rememberMenuStrings
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MusicMiniPlayer(
    model: BellStore,
    state: BellState,
    modifier: Modifier = Modifier,
    transitionContent: Modifier = Modifier,
    tabletMode: Boolean = false,
    onPanel: (PlayerPanel) -> Unit = {},
    open: () -> Unit,
) {
    val text_next_track = stringResource(Res.string.next_track)
    val text_previous_track = stringResource(Res.string.previous_track)
    val text_pause = stringResource(Res.string.pause)
    val text_play = stringResource(Res.string.play)
    val text_shuffle = stringResource(Res.string.shuffle)
    val text_repeat_all = stringResource(Res.string.repeat_all)
    val text_repeat_one = stringResource(Res.string.repeat_one)
    val text_lyrics = stringResource(Res.string.lyrics)
    val text_queue = stringResource(Res.string.playback_queue)
    val text_more = stringResource(Res.string.more)

    val dark = LocalMusicDark.current
    val color = if (dark) Color.White else Color.Black
    val activeColor = MusicAccent
    val menuStrings = rememberMenuStrings()
    val share = rememberMusicSharer()
    val motion = LocalPlayerMotion.current
    val reportVisualBounds = remember(motion) { motion?.let { it::updateMiniGlassVisualBounds } }
    MusicGlass(
        modifier.fillMaxWidth().height(48.dp).onSizeChanged { motion?.miniGlassSize = it },
        24.dp,
        open,
        reportVisualBounds,
    ) {
        Row(
            Modifier.fillMaxSize()
                .then(transitionContent)
                .onGloballyPositioned { motion?.miniContentCoordinates = it }
                .padding(start = if (tabletMode) 8.dp else 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tabletMode) {
                IconTouch(
                    "shuffle",
                    text_shuffle,
                    { model.accept(BellIntent.Shuffle) },
                    if (state.shuffle) activeColor else color,
                    18.dp,
                    32.dp,
                )
                IconTouch(
                    "backward",
                    text_previous_track,
                    { model.accept(BellIntent.Previous) },
                    color,
                    19.dp,
                    32.dp,
                )
                IconTouch(
                    if (state.current == null || state.audio.paused) "play" else "pause",
                    if (state.audio.paused) text_play else text_pause,
                    {
                        if (state.current == null) model.accept(BellIntent.Play(state.songs))
                        else model.accept(BellIntent.Toggle)
                    },
                    color,
                    21.dp,
                    34.dp,
                )
                IconTouch(
                    "forward",
                    text_next_track,
                    { model.accept(BellIntent.Next()) },
                    if (state.current == null) color.copy(alpha = .45f) else color,
                    20.dp,
                    32.dp,
                )
                IconTouch(
                    if (state.repeatOne) "repeatOne" else "repeat",
                    if (state.repeatOne) text_repeat_one else text_repeat_all,
                    { model.accept(BellIntent.Repeat) },
                    if (state.repeat) activeColor else color,
                    18.dp,
                    32.dp,
                )
                Spacer(Modifier.width(6.dp))
            }
            Crossfade(
                state.current?.let { model.artwork(it) },
                Modifier.size(30.dp),
                animationSpec = tween(100),
            ) { cover ->
                val coverItem = remember(cover) { state.current }
                MusicArtwork(
                    model,
                    state,
                    coverItem,
                    Modifier.fillMaxSize(),
                    5.dp,
                    sharedKey = coverItem?.let { "playing:${it.id}" },
                )
            }
            Spacer(Modifier.width(8.dp))
            MusicMiniMetadata(model, state, color, Modifier.weight(1f).fillMaxHeight())
            if (tabletMode) {
                if (state.current != null)
                    MusicMenu(
                        songMenu(
                            menuStrings,
                            model,
                            state,
                            state.current!!,
                            share,
                            leavePlayer = open,
                            offlineOnly = LocalMusicOffline.current,
                        ),
                        Modifier.size(32.dp),
                        MusicMenuStyle.Plain,
                        anchorLabel = text_more,
                        anchorIconSize = 18.dp,
                    )
                IconTouch(
                    "lyrics.fill",
                    text_lyrics,
                    { onPanel(PlayerPanel.Lyrics) },
                    color,
                    18.dp,
                    32.dp,
                )
                IconTouch(
                    "queue",
                    text_queue,
                    { onPanel(PlayerPanel.Queue) },
                    color,
                    18.dp,
                    32.dp,
                )
            } else {
                IconTouch(
                    if (state.current == null || state.audio.paused) "play" else "pause",
                    if (state.audio.paused) text_play else text_pause,
                    {
                        if (state.current == null) model.accept(BellIntent.Play(state.songs))
                        else model.accept(BellIntent.Toggle)
                    },
                    color,
                    20.dp,
                    36.dp,
                )
                IconTouch(
                    "forward",
                    text_next_track,
                    { model.accept(BellIntent.Next()) },
                    if (state.current == null) color.copy(alpha = .45f) else color,
                    25.dp,
                    36.dp,
                    modifier =
                        Modifier.width(36.dp * (1f - LocalMusicNavigationCompact.current))
                            .alpha(1f - LocalMusicNavigationCompact.current),
                )
            }
        }
    }
}
