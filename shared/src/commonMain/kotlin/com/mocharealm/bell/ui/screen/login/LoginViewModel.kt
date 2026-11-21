// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.login

import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class LoginState(val loading: Boolean, val error: MusicError?)

data class LoginForm(val server: String = "", val user: String = "", val password: String = "")

sealed interface LoginIntent {
    data class ServerChanged(val value: String) : LoginIntent

    data class UserChanged(val value: String) : LoginIntent

    data class PasswordChanged(val value: String) : LoginIntent

    data object Submit : LoginIntent

    data class Login(val server: String, val user: String, val password: String) : LoginIntent
}

class LoginViewModel(private val store: BellStore) :
    ScreenViewModel<LoginState, LoginIntent>(store, { s -> LoginState(s.loading, s.error) }) {
    private val formMutable = kotlinx.coroutines.flow.MutableStateFlow(LoginForm())
    val form: kotlinx.coroutines.flow.StateFlow<LoginForm> = formMutable

    override fun accept(intent: LoginIntent) {
        val current = formMutable.value
        when (intent) {
            is LoginIntent.ServerChanged -> {
                formMutable.value = current.copy(server = intent.value)
                return
            }
            is LoginIntent.UserChanged -> {
                formMutable.value = current.copy(user = intent.value)
                return
            }
            is LoginIntent.PasswordChanged -> {
                formMutable.value = current.copy(password = intent.value)
                return
            }
            LoginIntent.Submit -> {
                store.accept(BellIntent.Login(current.server, current.user, current.password))
                formMutable.value = current.copy(password = "")
                return
            }
            else -> Unit
        }
        store.accept(
            when (intent) {
                is LoginIntent.Login ->
                    BellIntent.Login(intent.server, intent.user, intent.password)
                else -> return
            }
        )
    }
}
