// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.login

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.BellButton
import com.mocharealm.bell.ui.composables.BellField
import com.mocharealm.bell.ui.composables.BellFormSurface
import com.mocharealm.bell.ui.composables.BellGroup
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.referenceTopInset
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.utils.displayText
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoginScreen(state: BellState, model: BellStore) {
    val text_app_name = stringResource(Res.string.app_name)
    val text_connect_library = stringResource(Res.string.connect_library)
    val text_connecting = stringResource(Res.string.connecting)
    val text_login_subtitle = stringResource(Res.string.login_subtitle)
    val text_password = stringResource(Res.string.password)
    val text_server_address = stringResource(Res.string.server_address)
    val text_username = stringResource(Res.string.username)

    val viewModel = rememberScreenViewModel(model) { LoginViewModel(model) }
    val observedState by viewModel.state.collectAsState()
    val screenState = if (state === model.state.value) observedState else viewModel.snapshot(state)
    val errorText = screenState.error?.displayText()

    val form by viewModel.form.collectAsState()
    val server = form.server
    val user = form.user
    val password = form.password
    BellFormSurface {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(horizontal = 20.dp, vertical = referenceTopInset() + 14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { MusicText(text_app_name, 34, weight = FontWeight.Bold) }
            item { MusicText(text_login_subtitle, 20, color = Color(0xFF737373)) }
            item {
                BellGroup {
                    BellField(
                        server,
                        { viewModel.accept(LoginIntent.ServerChanged(it)) },
                        text_server_address,
                    )
                    BellField(
                        user,
                        { viewModel.accept(LoginIntent.UserChanged(it)) },
                        text_username,
                    )
                    BellField(
                        password,
                        { viewModel.accept(LoginIntent.PasswordChanged(it)) },
                        text_password,
                        true,
                    )
                }
            }
            errorText?.let { item { MusicText(it, 15, MusicAccent, lines = 3) } }
            item {
                BellButton(
                    if (screenState.loading) text_connecting else text_connect_library,
                    { viewModel.accept(LoginIntent.Submit) },
                    Modifier.fillMaxWidth(),
                    !screenState.loading && user.isNotBlank() && server.isNotBlank(),
                )
            }
        }
    }
}
