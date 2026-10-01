@file:Suppress("DEPRECATION")

package com.anisync.android.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for signed-in accounts and which one is active.
 *
 * Storage split:
 *  - **Secrets** (session cookies, and the password when the user chose to keep it) live in
 *    EncryptedSharedPreferences, keyed per account.
 *  - **Metadata** (server, username, flags) and the active id live in a plain prefs file as JSON, so
 *    the account list renders without decrypting anything.
 *
 * Pure persistence: no network, no Room. Orchestration lives in [AccountManager].
 */
@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val secure: SharedPreferences = createSecurePrefs()
    private val metaPrefs: SharedPreferences =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccount: StateFlow<Account?> = _activeAccount.asStateFlow()

    private var activeId: Int? = null

    init {
        forgetAniListAccounts()
        load()
    }

    /** The id of the account for [username] on [serverUrl], if it is already known. */
    fun findId(serverUrl: String, username: String): Int? =
        _accounts.value.firstOrNull { it.serverUrl == serverUrl && it.username.equals(username, ignoreCase = true) }?.id

    /** A fresh id for a new account. */
    @Synchronized
    fun newId(): Int {
        val next = metaPrefs.getInt(KEY_NEXT_ID, 1)
        metaPrefs.edit().putInt(KEY_NEXT_ID, next + 1).apply()
        return next
    }

    /**
     * Inserts a new account or replaces an existing one with the same id (signing in again). Does not
     * change which account is active — callers switch explicitly via [switchTo].
     */
    fun addOrReplace(account: Account) {
        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == account.id }
        if (idx >= 0) list[idx] = account else list.add(account)
        _accounts.value = list
        persistMeta()
        recompute()
    }

    /** Sets the active account. `null` means "no active account" (drops to the login screen). */
    fun switchTo(id: Int?) {
        if (id != null && _accounts.value.none { it.id == id }) return
        activeId = id
        metaPrefs.edit().putInt(KEY_ACTIVE_ID, id ?: NO_ACTIVE).apply()
        recompute()
    }

    /**
     * Removes an account and its secrets. Refuses to remove the **active** account — the caller must
     * [switchTo] another (or `null`) first, so the app never ends up active on a deleted account.
     */
    fun remove(id: Int) {
        if (id == activeId) return
        secure.edit().remove(passwordKey(id)).remove(cookiesKey(id)).apply()
        _accounts.value = _accounts.value.filterNot { it.id == id }
        persistMeta()
    }

    /** Marks the active account's session as expired and clears the active slot. */
    fun markActiveExpired() {
        val active = _activeAccount.value ?: return
        markExpired(active.id)
    }

    /**
     * Marks an account's session expired. If it is the active account, also clears the active slot
     * (drops to the login screen). Another account is just flagged.
     */
    fun markExpired(id: Int) {
        val account = _accounts.value.firstOrNull { it.id == id } ?: return
        replaceById(account.copy(sessionExpired = true))
        if (id == activeId) switchTo(null)
    }

    fun setCalendarToken(id: Int, token: String?) {
        val account = _accounts.value.firstOrNull { it.id == id } ?: return
        if (account.calendarToken != token) replaceById(account.copy(calendarToken = token))
    }

    /** Kept only when the user asks to, so an expired session can sign in again by itself. */
    fun setPassword(id: Int, password: String?) {
        secure.edit().apply {
            if (password == null) remove(passwordKey(id)) else putString(passwordKey(id), password)
        }.apply()
    }

    fun password(id: Int): String? = secure.getString(passwordKey(id), null)

    fun hasPassword(id: Int): Boolean = secure.contains(passwordKey(id))

    fun cookies(id: Int): List<Cookie> {
        val account = _accounts.value.firstOrNull { it.id == id }
        val url = (account?.serverUrl ?: return emptyList()).toHttpUrlOrNull() ?: return emptyList()
        val stored = secure.getString(cookiesKey(id), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(stored) }.getOrDefault(emptyList())
            .mapNotNull { Cookie.parse(url, it) }
    }

    fun saveCookies(id: Int, cookies: List<Cookie>) {
        secure.edit().putString(cookiesKey(id), json.encodeToString(cookies.map { it.toString() })).apply()
    }

    private fun replaceById(updated: Account) {
        _accounts.value = _accounts.value.map { if (it.id == updated.id) updated else it }
        persistMeta()
        recompute()
    }

    private fun recompute() {
        _activeAccount.value = _accounts.value.firstOrNull { it.id == activeId }
    }

    private fun persistMeta() {
        val metas = _accounts.value.map {
            AccountMeta(it.id, it.serverUrl, it.username, it.avatarUrl, it.sessionExpired, it.calendarToken)
        }
        metaPrefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(metas)).apply()
    }

    private fun load() {
        val metasJson = metaPrefs.getString(KEY_ACCOUNTS, null)
        val metas = if (metasJson != null) {
            runCatching { json.decodeFromString<List<AccountMeta>>(metasJson) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val loaded = metas.map {
            Account(it.id, it.serverUrl, it.username, it.avatarUrl, it.sessionExpired, it.calendarToken)
        }
        _accounts.value = loaded

        val storedActive = metaPrefs.getInt(KEY_ACTIVE_ID, NO_ACTIVE)
            .takeIf { it != NO_ACTIVE && loaded.any { a -> a.id == it } }
        // Can't start active on an expired session — keep the account listed but sign in again.
        activeId = storedActive?.takeUnless { id -> loaded.first { it.id == id }.isExpired }
        recompute()
    }

    /**
     * The AniList client kept its accounts and OAuth tokens in other files. None of it means
     * anything to a Yamtrack server, so it is removed rather than left encrypted on the device.
     */
    private fun forgetAniListAccounts() {
        if (metaPrefs.getBoolean(KEY_ANILIST_FORGOTTEN, false)) return
        runCatching { context.deleteSharedPreferences(LEGACY_SECURE_PREFS) }
        runCatching { context.deleteSharedPreferences(LEGACY_META_PREFS) }
        metaPrefs.edit().putBoolean(KEY_ANILIST_FORGOTTEN, true).apply()
    }

    // ── Encrypted store creation (with keystore-invalidation recovery) ──────────────────────────

    private fun buildMasterKey(): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun createSecurePrefs(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS,
                buildMasterKey(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { it.contains(KEY_PROBE) } // force decrypt to surface AEADBadTagException now
        } catch (t: Throwable) {
            // Keystore key invalidated (OS upgrade, backup/restore, lockscreen change, etc.).
            // Wipe the unreadable prefs, then recreate. Sessions are lost; the user signs in again.
            Log.w(TAG, "EncryptedSharedPreferences unreadable, resetting", t)
            resetEncryptedStore()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS,
                buildMasterKey(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun resetEncryptedStore() {
        runCatching { context.deleteSharedPreferences(SECURE_PREFS) }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    @Serializable
    private data class AccountMeta(
        val id: Int,
        val serverUrl: String,
        val username: String,
        val avatarUrl: String? = null,
        val sessionExpired: Boolean = false,
        val calendarToken: String? = null,
    )

    companion object {
        private const val TAG = "AccountStore"
        private const val SECURE_PREFS = "yamtrack_auth"
        private const val META_PREFS = "yamtrack_accounts"
        private const val LEGACY_SECURE_PREFS = "auth_prefs"
        private const val LEGACY_META_PREFS = "anisync_accounts"
        private const val MASTER_KEY_ALIAS = MasterKey.DEFAULT_MASTER_KEY_ALIAS
        private const val KEY_PROBE = "probe"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_NEXT_ID = "next_id"
        private const val KEY_ANILIST_FORGOTTEN = "anilist_forgotten"
        private const val NO_ACTIVE = Int.MIN_VALUE

        private fun passwordKey(id: Int) = "password_$id"
        private fun cookiesKey(id: Int) = "cookies_$id"
    }
}
