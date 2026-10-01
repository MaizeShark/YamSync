package com.anisync.android.data.yamtrack

import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.util.ApiError
import com.anisync.android.data.yamtrack.html.HtmlYamtrackApi
import com.anisync.android.data.yamtrack.html.YamtrackCookieStore
import com.anisync.android.data.yamtrack.html.YamtrackSession
import okhttp3.Cookie
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out one [YamtrackApi] per account, each with its own session cookies, so a background sync
 * of one account never signs another out.
 */
@Singleton
class YamtrackClients @Inject constructor(
    private val accountStore: AccountStore,
) {
    /** Shared connection pool; every session layers its own cookie jar on top. */
    private val http = OkHttpClient()

    private val clients = mutableMapOf<Int, YamtrackApi>()

    /** The client for the active account. Throws `SessionExpired` when nobody is signed in. */
    fun active(): YamtrackApi {
        val account = accountStore.activeAccount.value ?: throw ApiError.SessionExpired()
        return forAccount(account.id)
    }

    @Synchronized
    fun forAccount(id: Int): YamtrackApi = clients.getOrPut(id) {
        val account = accountStore.accounts.value.firstOrNull { it.id == id }
            ?: throw ApiError.SessionExpired()
        HtmlYamtrackApi(
            YamtrackSession(
                baseUrl = account.serverUrl,
                cookieStore = object : YamtrackCookieStore {
                    override fun load(): List<Cookie> = accountStore.cookies(id)
                    override fun save(cookies: List<Cookie>) = accountStore.saveCookies(id, cookies)
                },
                credentials = { accountStore.password(id)?.let { account.username to it } },
                client = http
            )
        )
    }

    /** Drops the cached client, e.g. after the account was removed or signed in again. */
    @Synchronized
    fun evict(id: Int) {
        clients.remove(id)
    }

    /**
     * A client for signing in to [serverUrl] before there is an account to keep its session.
     * [cookies] collects what the server sets, to hand to the account once it exists.
     */
    fun forLogin(serverUrl: String, cookies: MutableList<Cookie>): YamtrackApi =
        HtmlYamtrackApi(
            YamtrackSession(
                baseUrl = serverUrl,
                cookieStore = object : YamtrackCookieStore {
                    override fun load(): List<Cookie> = synchronized(cookies) { cookies.toList() }
                    override fun save(cookies: List<Cookie>) = replaceAll(cookies)
                    private fun replaceAll(saved: List<Cookie>) {
                        synchronized(cookies) {
                            cookies.clear()
                            cookies.addAll(saved)
                        }
                    }
                },
                credentials = { null },
                client = http
            )
        )
}
