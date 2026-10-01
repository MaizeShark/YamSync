package com.anisync.android.data.yamtrack.html

import com.anisync.android.data.util.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Where a session's cookies live between app runs. */
interface YamtrackCookieStore {
    fun load(): List<Cookie>
    fun save(cookies: List<Cookie>)
}

/** Username and password to sign in again with, or null when the user chose not to keep them. */
fun interface YamtrackCredentials {
    suspend fun get(): Pair<String, String>?
}

/**
 * One signed-in browser session against a Yamtrack server.
 *
 * Yamtrack only speaks HTML forms behind Django's session login, so this does what a browser does:
 * keeps the `sessionid` and `csrftoken` cookies, sends the CSRF token back on every POST along with
 * a matching Referer (Django checks it over HTTPS), and signs in again when the session runs out.
 *
 * Redirects are never followed. A redirect to the login page is how Django says the session is
 * gone, and a write answered with a redirect is a write that went through; following them would
 * hide both.
 *
 * [baseUrl] may carry a path, for servers set up under a sub-path with `BASE_URL`.
 */
class YamtrackSession(
    baseUrl: String,
    private val cookieStore: YamtrackCookieStore,
    private val credentials: YamtrackCredentials,
    client: OkHttpClient = OkHttpClient()
) {
    /** The server root, always without a trailing slash, e.g. `https://host/yamtrack`. */
    val baseUrl: HttpUrl = normalizeBaseUrl(baseUrl)

    private val jar = PersistentCookieJar(cookieStore)

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(jar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Serialises re-logins so a burst of expired requests signs in once, not once each. */
    private val loginMutex = Mutex()

    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        // Django's routes mix trailing slashes and none; keep whatever the caller wrote.
        if (path.endsWith('/') && path.length > 1) builder.addPathSegment("")
        query.forEach { (name, value) -> if (value != null) builder.addQueryParameter(name, value) }
        return builder.build()
    }

    /** The CSRF token Django expects back on writes, from its cookie. */
    private fun csrfToken(): String? =
        jar.loadForRequest(baseUrl).firstOrNull { it.name == "csrftoken" }?.value

    val hasSession: Boolean
        get() = jar.loadForRequest(baseUrl).any { it.name == "sessionid" }

    /**
     * GETs [path] and returns the body. Signs in again once if the session has run out.
     * [htmx] asks for the partial an HTMX request would get instead of the full page.
     */
    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), htmx: Boolean = false): String =
        withReauth { execute(getRequest(url(path, query), htmx)) }.use { response ->
            when {
                response.isSuccessful -> response.body?.string().orEmpty()
                response.code == 404 -> throw ApiError.Unknown("Not found on the server: $path")
                response.code in 300..399 ->
                    throw ApiError.Unknown("Unexpected redirect from $path to ${response.header("Location")}")
                else -> throw ApiError.Unknown("The server answered $path with ${response.code}")
            }
        }

    /** GETs a page that needs no session (the calendar feed is addressed by token). */
    suspend fun getPublic(path: String): String = execute(getRequest(url(path), htmx = false)).use { response ->
        when {
            response.isSuccessful -> response.body?.string().orEmpty()
            response.code == 401 || response.code == 404 -> throw ApiError.PermissionDenied()
            else -> throw ApiError.ServerError(response.code)
        }
    }

    /**
     * POSTs [form] to [path]. Returns the response for the caller to inspect; Yamtrack answers
     * most writes with a redirect, a few with a fragment or JSON.
     */
    suspend fun post(path: String, form: Map<String, String>): PostResult = withReauth {
        // Django only sets the CSRF cookie once a page that uses it has been served. A session
        // restored from disk normally has it; one that does not gets it from any page.
        if (csrfToken() == null) execute(getRequest(url("/"), htmx = false)).close()
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url(path))
            .post(body)
            .header("X-CSRFToken", csrfToken().orEmpty())
            .header("Referer", baseUrl.toString().trimEnd('/') + "/")
            .build()
        execute(request)
    }.use { response ->
        PostResult(response.code, response.header("Location"), response.body?.string().orEmpty())
    }

    /** Signs in with [username] and [password], replacing any session this one had. */
    suspend fun login(username: String, password: String) {
        jar.clear()
        val loginUrl = url("/accounts/login/")
        val page = execute(getRequest(loginUrl, htmx = false)).use { response ->
            if (!response.isSuccessful) throw ApiError.ServerError(response.code)
            response.body?.string().orEmpty()
        }
        val form = Jsoup.parse(page).selectFirst("form input[name=csrfmiddlewaretoken]")
            ?: throw if (page.contains("socialaccount", ignoreCase = true)) {
                ApiError.LoginFailed("This server only allows signing in through its single sign-on.")
            } else {
                ApiError.ParseError("login", "no login form")
            }
        val body = FormBody.Builder()
            .add("csrfmiddlewaretoken", form.attr("value"))
            .add("login", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url(loginUrl)
            .post(body)
            .header("Referer", loginUrl.toString())
            .build()
        execute(request).use { response ->
            // Success redirects away from the login page; failure re-renders the form with errors.
            if (response.code in 300..399 && !isLoginRedirect(response.header("Location"))) return
            val errors = Jsoup.parse(response.body?.string().orEmpty())
                .select(".errorlist li, [role=alert], .text-red-400, .text-red-500")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            throw ApiError.LoginFailed(errors.firstOrNull())
        }
    }

    /** Forgets the session on this device; the server keeps it until it expires. */
    fun clear() = jar.clear()

    private suspend fun <T : AutoCloseable> withReauth(block: suspend () -> T): T {
        val first = block()
        if (!needsLogin(first)) return first
        first.close()
        renewSession()
        val second = block()
        if (needsLogin(second)) {
            second.close()
            throw ApiError.SessionExpired()
        }
        return second
    }

    private suspend fun renewSession() = loginMutex.withLock {
        val (username, password) = credentials.get() ?: throw ApiError.SessionExpired()
        try {
            login(username, password)
        } catch (e: ApiError.LoginFailed) {
            // The stored password no longer works; the user has to sign in again by hand.
            throw ApiError.SessionExpired()
        }
    }

    /**
     * Whether [response] says the session is gone: a redirect to the login page, or a 403 on a
     * write, which is what Django's CSRF check answers once the session and its token are stale.
     */
    private fun needsLogin(response: AutoCloseable): Boolean {
        if (response !is Response) return false
        return when {
            response.code in 300..399 -> isLoginRedirect(response.header("Location"))
            response.code == 403 && response.request.method == "POST" -> true
            else -> false
        }
    }

    private fun isLoginRedirect(location: String?): Boolean =
        location != null && location.contains("/accounts/login")

    private fun getRequest(url: HttpUrl, htmx: Boolean): Request = Request.Builder()
        .url(url)
        .get()
        .apply { if (htmx) header("HX-Request", "true") }
        .build()

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw ApiError.Timeout(e)
        } catch (e: InterruptedIOException) {
            throw ApiError.Timeout(e)
        } catch (e: IOException) {
            throw ApiError.Offline(e)
        }
        if (response.code >= 500) {
            response.close()
            throw ApiError.ServerError(response.code)
        }
        response
    }

    companion object {
        /** Accepts what a user types: missing scheme, trailing slash, a path under the host. */
        fun normalizeBaseUrl(input: String): HttpUrl {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            return withScheme.toHttpUrl()
        }
    }
}

/** What a POST came back with. */
data class PostResult(val code: Int, val location: String?, val body: String) {
    val isRedirect: Boolean get() = code in 300..399
}

/** Keeps cookies in memory and writes every change through to [store]. */
private class PersistentCookieJar(private val store: YamtrackCookieStore) : CookieJar {
    private val cookies = mutableListOf<Cookie>().apply { addAll(store.load()) }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        store.save(this.cookies.toList())
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        store.save(emptyList())
    }
}
