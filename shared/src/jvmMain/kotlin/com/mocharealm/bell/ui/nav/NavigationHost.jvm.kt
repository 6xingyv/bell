// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.MusicHeader
import com.mocharealm.bell.ui.composables.MusicText
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MusicNavigationHost(
    route: String,
    selectedTab: String,
    model: BellStore,
    header: (String) -> MusicHeader,
    onBack: (String) -> Unit,
    onTab: (String) -> Unit,
    onSearch: () -> Unit,
    background: @Composable (String) -> Unit,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val text_app_name = stringResource(Res.string.app_name)
    val text_back = stringResource(Res.string.back)
    val text_search = stringResource(Res.string.search)

    Box(Modifier.fillMaxSize()) {
        background(route)
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(160.dp).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MusicText(text_app_name, 24, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                listOf(Routes.Home, Routes.Library).forEach { label ->
                    TextButton({ onTab(label) }) { Text(routeTitle(label)) }
                }
                TextButton(onSearch) { Text(text_search) }
                if (!header(route).root) TextButton({ onBack(selectedTab) }) { Text(text_back) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                content(route)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp)) {
                    miniPlayer()
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
actual fun MusicPage(
    title: String,
    largeTitle: Boolean,
    horizontalInset: Dp,
    underHeader: Boolean,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    scrollToTopKey: Any?,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(scrollToTopKey) {
        if (scrollToTopKey != null) list.scrollToItem(0)
    }
    val pageList: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding =
                PaddingValues(
                    start = horizontalInset,
                    end = horizontalInset,
                    top = if (underHeader) 0.dp else 40.dp,
                    bottom = 96.dp,
                ),
        ) {
            if (largeTitle)
                item {
                    MusicText(
                        title,
                        34,
                        if (LocalMusicDark.current) Color.White else Color.Black,
                        androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier =
                            Modifier.padding(
                                horizontal = (20.dp - horizontalInset).coerceAtLeast(0.dp)
                            ),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            content()
        }
    }
    if (onRefresh == null) pageList()
    else
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            pageList()
        }
}

@Composable actual fun MusicAlphabetIndex(sections: List<Pair<String, Int>>) {}
