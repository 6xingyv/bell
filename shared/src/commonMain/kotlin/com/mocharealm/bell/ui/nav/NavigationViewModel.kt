// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import kotlinx.coroutines.flow.*

data class NavigationState(val tab: String = Routes.Library, val route: String = Routes.Library)

sealed interface NavigationIntent {
    data class Navigate(val route: String) : NavigationIntent

    data class SelectTab(val route: String) : NavigationIntent
}

class NavigationViewModel {
    private val mutable = MutableStateFlow(NavigationState())
    val state = mutable.asStateFlow()

    fun accept(intent: NavigationIntent) {
        mutable.update {
            when (intent) {
                is NavigationIntent.Navigate -> it.copy(route = intent.route)
                is NavigationIntent.SelectTab -> NavigationState(intent.route, intent.route)
            }
        }
    }
}
