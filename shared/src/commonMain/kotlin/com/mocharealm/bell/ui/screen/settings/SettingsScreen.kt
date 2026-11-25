// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.settings

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.CoverListRow
import com.mocharealm.bell.ui.composables.DetailList
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.screen.settings.composables.PlainAction
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(model: BellStore, state: BellState, back: () -> Unit) {
    val text_account = stringResource(Res.string.account)
    val text_refresh_library = stringResource(Res.string.refresh_library)
    val text_sign_out = stringResource(Res.string.sign_out)
    val text_user_avatar = stringResource(Res.string.user_avatar)

    val viewModel = rememberScreenViewModel(model) { SettingsViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)

    DetailList(text_account, back) {
        item {
            CoverListRow(
                screenState.session?.user?.name.orEmpty(),
                screenState.session?.server,
                {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF879CC4)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicText(
                            screenState.session?.user?.name?.take(1).orEmpty(),
                            22,
                            Color.White,
                        )
                        AsyncImage(
                            screenState.session?.let {
                                model.repository.avatar(it, screenState.userImageTag)
                            },
                            text_user_avatar,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                },
            ) {}
        }
        item { PlainAction(text_refresh_library) { viewModel.accept(SettingsIntent.Refresh()) } }
        item { PlainAction(text_sign_out) { viewModel.accept(SettingsIntent.Logout) } }
    }
}
