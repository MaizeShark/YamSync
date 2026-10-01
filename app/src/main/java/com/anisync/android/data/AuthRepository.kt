package com.anisync.android.data

import com.anisync.android.data.account.AccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth facade over [AccountStore] for the session surface: the login/main swap in
 * [com.anisync.android.MainActivity] and the expired-session handling of the repositories.
 * Account mutations that need the network live in [com.anisync.android.data.account.AccountManager].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val accountStore: AccountStore,
) {
    /** True whenever there is an active account. Drives the Login ↔ Main swap in MainActivity. */
    val isLoggedIn: Flow<Boolean> = accountStore.activeAccount.map { it != null }

    /**
     * Emitted when the active account's session ran out and could not be renewed. The UI collects
     * this to show a "session expired" dialog and drop to the login screen.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    /**
     * Called when a request for the active account ends in an expired session. Marks **only the
     * active** account expired and clears the active slot (other accounts are kept), then emits the
     * session-expired event.
     */
    fun onSessionExpired() {
        accountStore.markActiveExpired()
        _sessionExpired.tryEmit(Unit)
    }
}
