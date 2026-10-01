package com.anisync.android.data.account

import android.content.Context
import com.anisync.android.data.AppSettings
import com.anisync.android.data.local.dao.AiringScheduleDao
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.util.safeApiCall
import com.anisync.android.data.yamtrack.YamtrackClients
import com.anisync.android.data.yamtrack.html.YamtrackSession
import com.anisync.android.domain.PreferencesRepository
import com.anisync.android.domain.Result
import com.anisync.android.widget.core.WidgetRefresh
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates account sign-in / switch / remove and the local-state reset that makes switching safe.
 *
 * Switching is "reset + rebuild": per-account preferences are cleared, the new account is activated
 * and [sessionEpoch] is bumped. The UI keys the whole `MainScreen` subtree on [sessionEpoch], so a
 * bump tears down the NavController and every screen ViewModel and rebuilds them, which then read
 * the new account's data. Room rows are scoped by account id, so they stay and show instantly on
 * switching back.
 *
 * Mutations run on an internal [scope] (not a caller's viewModelScope) so the subtree rebuild they
 * trigger can't cancel them mid-flight and strand the busy loader.
 */
@Singleton
class AccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountStore: AccountStore,
    private val clients: YamtrackClients,
    private val libraryDao: LibraryDao,
    private val airingScheduleDao: AiringScheduleDao,
    private val preferencesRepository: PreferencesRepository,
    private val appSettings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val accounts: StateFlow<List<Account>> get() = accountStore.accounts
    val activeAccount: StateFlow<Account?> get() = accountStore.activeAccount

    /**
     * Increments on every explicit active-account change (sign-in / switch / remove-active / logout).
     * The UI keys `MainScreen` on this to force a full ViewModel rebuild.
     */
    private val _sessionEpoch = MutableStateFlow(0)
    val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

    /** True while a sign-in/switch/remove is in flight, so the UI can show a blocking loader. */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * Signs [username] in to the Yamtrack server at [serverUrl] and makes that account active. Used
     * for the first sign-in, adding another account, and signing in again after a session expired.
     *
     * [rememberPassword] keeps the password in encrypted storage so an expired session renews itself;
     * without it, the user is asked to sign in again when the server ends the session.
     */
    suspend fun signIn(
        serverUrl: String,
        username: String,
        password: String,
        rememberPassword: Boolean
    ): Result<Account> = busy {
        val normalized = runCatching { YamtrackSession.normalizeBaseUrl(serverUrl).toString().trimEnd('/') }
            .getOrElse { return@busy Result.Error(it.message ?: "Invalid server address") }
        val cookies = mutableListOf<Cookie>()
        val client = clients.forLogin(normalized, cookies)
        when (val result = safeApiCall { client.login(username.trim(), password) }) {
            is Result.Error -> result
            is Result.Success -> {
                val user = result.data
                val id = accountStore.findId(normalized, user.username) ?: accountStore.newId()
                val account = Account(
                    id = id,
                    serverUrl = normalized,
                    username = user.username,
                    calendarToken = user.token
                )
                val wasActive = accountStore.activeAccount.value?.id == id
                accountStore.addOrReplace(account)
                accountStore.saveCookies(id, synchronized(cookies) { cookies.toList() })
                accountStore.setPassword(id, password.takeIf { rememberPassword })
                clients.evict(id)
                if (!wasActive) {
                    appSettings.clearAccountScoped()
                    accountStore.switchTo(id)
                    bumpEpoch()
                    scope.launch { refreshWidgets() }
                }
                Result.Success(account)
            }
        }
    }

    /** Switches the active account. Fire-and-forget. */
    fun switch(id: Int) {
        if (accountStore.activeAccount.value?.id == id) return
        scope.launch {
            busy {
                appSettings.clearAccountScoped()
                accountStore.switchTo(id)
                bumpEpoch()
            }
            refreshWidgets()
        }
    }

    /**
     * Removes an account. If it is the active one, switches to another (or to "none") first;
     * otherwise just drops it. Its cached library and schedule go with it. Fire-and-forget.
     */
    fun removeAccount(id: Int) {
        scope.launch {
            busy {
                if (accountStore.activeAccount.value?.id == id) {
                    val next = accountStore.accounts.value.firstOrNull { it.id != id && !it.isExpired }
                    appSettings.clearAccountScoped()
                    accountStore.switchTo(next?.id)
                    accountStore.remove(id)
                    bumpEpoch()
                    scope.launch { refreshWidgets() }
                } else {
                    accountStore.remove(id)
                }
                clients.evict(id)
                withContext(Dispatchers.IO) {
                    runCatching { libraryDao.deleteForOwner(id) }
                    runCatching { airingScheduleDao.clearAll(id) }
                }
                preferencesRepository.clearForAccount(id)
            }
        }
    }

    /** Logs out the active account (switches to another if present, else to the login screen). */
    fun logoutActive() {
        val active = accountStore.activeAccount.value ?: return
        removeAccount(active.id)
    }

    /** Whether [id] keeps a password to renew its session with. */
    fun remembersPassword(id: Int): Boolean = accountStore.hasPassword(id)

    private fun bumpEpoch() {
        _sessionEpoch.value += 1
    }

    private suspend fun <T> busy(block: suspend () -> T): T {
        _isBusy.value = true
        return try {
            block()
        } finally {
            _isBusy.value = false
        }
    }

    private fun refreshWidgets() {
        runCatching { WidgetRefresh.all(context) }
    }
}
