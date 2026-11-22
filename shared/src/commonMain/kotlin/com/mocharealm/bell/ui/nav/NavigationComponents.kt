// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.MusicHeader

@Composable
expect fun MusicNavigationHost(
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
)

@Composable
expect fun MusicPage(
    title: String,
    largeTitle: Boolean = true,
    horizontalInset: Dp = 20.dp,
    underHeader: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
)

@Composable expect fun MusicAlphabetIndex(sections: List<Pair<String, Int>>)
