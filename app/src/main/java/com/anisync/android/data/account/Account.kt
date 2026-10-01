package com.anisync.android.data.account

import java.net.URI

/**
 * A signed-in Yamtrack account: one user on one server.
 *
 * [id] is assigned on this device; Room rows and notification tags are scoped by it. The session
 * cookies and the optional stored password live in encrypted storage (see [AccountStore]), never in
 * this object.
 */
data class Account(
    val id: Int,
    /** The server root as the user entered it, normalised: scheme, host and any sub-path. */
    val serverUrl: String,
    val username: String,
    val avatarUrl: String? = null,
    /** Set when the session ran out and could not be renewed; the user has to sign in again. */
    val sessionExpired: Boolean = false,
    /** Token for the server's calendar feed, read from its integrations page. */
    val calendarToken: String? = null,
) {
    /** What the app shows as the account's name. */
    val name: String get() = username

    val isExpired: Boolean get() = sessionExpired

    /** The server's host, for telling accounts on different servers apart. */
    val serverLabel: String
        get() = runCatching { URI(serverUrl).let { uri -> uri.host + (uri.path?.takeIf { it.length > 1 } ?: "") } }
            .getOrNull() ?: serverUrl
}
