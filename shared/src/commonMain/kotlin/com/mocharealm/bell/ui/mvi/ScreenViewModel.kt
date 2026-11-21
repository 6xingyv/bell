// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.mvi

import androidx.compose.runtime.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

abstract class ScreenViewModel<S, I>(store: BellStore, private val project: (BellState) -> S) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state: StateFlow<S> =
        store.state
            .map(project)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), project(store.state.value))

    fun snapshot(state: BellState): S = project(state)

    abstract fun accept(intent: I)

    fun close() {
        scope.cancel()
    }
}

@Composable
internal fun <T : ScreenViewModel<*, *>> rememberScreenViewModel(key: Any, create: () -> T): T {
    val viewModel = remember(key) { create() }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    return viewModel
}
