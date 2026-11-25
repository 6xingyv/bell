// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.search.composables

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.EmptyMusic
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicMenuSource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicSearchResults(model: BellStore, state: BellState, padding: PaddingValues) {
    val dark = LocalMusicDark.current
    val text_search_empty_description = stringResource(Res.string.search_empty_description)
    val text_search_empty_title = stringResource(Res.string.search_empty_title)

    MusicMenuSource {
        LazyColumn(
            Modifier.fillMaxSize().background(if (dark) Color.Black else Color.White),
            contentPadding =
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            items(state.results, key = { it.id }) { item ->
                MusicSearchRow(model, state, item, true)
            }
            if (state.results.isEmpty())
                item { EmptyMusic(text_search_empty_title, text_search_empty_description) }
        }
    }
}
