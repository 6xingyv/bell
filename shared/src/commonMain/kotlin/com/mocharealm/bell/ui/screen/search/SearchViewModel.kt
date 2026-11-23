// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.search

import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.SearchScope
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class SearchState(
    val results: List<Item>,
    val searchHistory: List<Item>,
    val genres: List<Item>,
    val searchScope: SearchScope,
)

sealed interface SearchIntent {
    data class Search(val query: String) : SearchIntent

    data object ClearSearchHistory : SearchIntent

    data class OpenItem(val item: Item, val rememberSearch: Boolean = false) : SearchIntent

    data class Favorite(val item: Item) : SearchIntent

    data class Enqueue(val item: Item) : SearchIntent
}

class SearchViewModel(private val store: BellStore) :
    ScreenViewModel<SearchState, SearchIntent>(
        store,
        { s -> SearchState(s.results, s.searchHistory, s.genres, s.searchScope) },
    ) {
    override fun accept(intent: SearchIntent) {
        store.accept(
            when (intent) {
                is SearchIntent.Search -> BellIntent.Search(intent.query)
                SearchIntent.ClearSearchHistory -> BellIntent.ClearSearchHistory
                is SearchIntent.OpenItem -> BellIntent.OpenItem(intent.item, intent.rememberSearch)
                is SearchIntent.Favorite -> BellIntent.Favorite(intent.item)
                is SearchIntent.Enqueue -> BellIntent.Enqueue(intent.item)
            }
        )
    }
}
