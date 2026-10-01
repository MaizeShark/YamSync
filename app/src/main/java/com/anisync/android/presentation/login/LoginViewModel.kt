package com.anisync.android.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.data.account.AccountManager
import com.anisync.android.domain.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
    /** Set when the form was prefilled because this account's session ran out. */
    val expiredAccount: String? = null
) {
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !isSigningIn
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accountManager: AccountManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** Prefills the account whose session ran out, so signing in again is one password away. */
    private fun initialState(): LoginUiState {
        val expired = accountManager.accounts.value.lastOrNull { it.isExpired }
            ?: accountManager.accounts.value.lastOrNull()
        return if (expired != null) {
            LoginUiState(
                serverUrl = expired.serverUrl,
                username = expired.username,
                expiredAccount = expired.username.takeIf { expired.isExpired }
            )
        } else {
            LoginUiState()
        }
    }

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, errorMessage = null) }

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, errorMessage = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }

    fun onRememberPasswordChange(value: Boolean) = _uiState.update { it.copy(rememberPassword = value) }

    fun signIn() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(isSigningIn = true, errorMessage = null) }
        viewModelScope.launch {
            val result = accountManager.signIn(
                serverUrl = state.serverUrl,
                username = state.username,
                password = state.password,
                rememberPassword = state.rememberPassword
            )
            // On success the active account changes and the activity swaps this screen out.
            _uiState.update {
                it.copy(
                    isSigningIn = false,
                    password = if (result is Result.Success) "" else it.password,
                    errorMessage = (result as? Result.Error)?.message
                )
            }
        }
    }
}
