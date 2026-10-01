package com.anisync.android.data.yamtrack

import com.anisync.android.data.AuthRepository
import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.util.ApiError
import com.anisync.android.data.util.safeApiCall
import com.anisync.android.domain.Result
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How repositories reach the active account's Yamtrack server.
 *
 * Runs the call through [safeApiCall], and when the session has run out for good (the server ended
 * it and no stored password could renew it), signs the account out so the app asks for the
 * password again instead of failing every request.
 */
@Singleton
class YamtrackGateway @Inject constructor(
    private val clients: YamtrackClients,
    private val accountStore: AccountStore,
    private val authRepository: AuthRepository,
) {
    /** The active account's local id, which scopes its Room rows; -1 when nobody is signed in. */
    val ownerId: Int get() = accountStore.activeAccount.value?.id ?: -1

    suspend fun <T> call(block: suspend YamtrackApi.() -> T): Result<T> {
        val result = safeApiCall { clients.active().block() }
        if (result is Result.Error && result.exception is ApiError.SessionExpired &&
            accountStore.activeAccount.value != null
        ) {
            authRepository.onSessionExpired()
        }
        return result
    }

    /** For background work on a specific account; never signs anyone out. */
    suspend fun <T> callFor(accountId: Int, block: suspend YamtrackApi.() -> T): Result<T> =
        safeApiCall { clients.forAccount(accountId).block() }
}
