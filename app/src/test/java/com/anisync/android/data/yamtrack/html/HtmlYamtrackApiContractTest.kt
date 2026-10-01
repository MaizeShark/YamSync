package com.anisync.android.data.yamtrack.html

import com.anisync.android.data.util.ApiError
import com.anisync.android.data.yamtrack.YamtrackEntryFields
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the real client against a live Yamtrack. Skipped unless `YAMTRACK_TEST_URL`,
 * `YAMTRACK_TEST_USER` and `YAMTRACK_TEST_PASSWORD` are set, because it needs a server and writes
 * to that user's library. Point it at a throwaway instance, never a real library.
 *
 * It checks the things the parser tests cannot: CSRF, redirects, re-login, and that a save really
 * lands and reads back.
 */
class HtmlYamtrackApiContractTest {

    private val url = System.getenv("YAMTRACK_TEST_URL")
    private val user = System.getenv("YAMTRACK_TEST_USER")
    private val password = System.getenv("YAMTRACK_TEST_PASSWORD")

    private class MemoryCookies : YamtrackCookieStore {
        var cookies: List<Cookie> = emptyList()
        override fun load() = cookies
        override fun save(cookies: List<Cookie>) { this.cookies = cookies }
    }

    private lateinit var cookies: MemoryCookies
    private lateinit var api: HtmlYamtrackApi
    private lateinit var session: YamtrackSession

    @Before
    fun setUp() {
        assumeTrue("set YAMTRACK_TEST_URL to run", url != null && user != null && password != null)
        cookies = MemoryCookies()
        session = YamtrackSession(url!!, cookies, { user!! to password!! })
        api = HtmlYamtrackApi(session)
    }

    @Test
    fun `login, then the session identifies the user`() = runBlocking {
        val me = api.login(user!!, password!!)
        assertEquals(user, me.username)
        assertTrue(session.hasSession)
    }

    @Test
    fun `wrong password is a login failure`() = runBlocking {
        try {
            api.login(user!!, "definitely-not-the-password")
            fail("expected LoginFailed")
        } catch (e: ApiError.LoginFailed) {
            // expected
        }
    }

    @Test
    fun `an expired session signs in again by itself`() = runBlocking {
        api.login(user!!, password!!)
        // Throw the session away as if it had expired; keep nothing.
        session.clear()
        assertEquals(user, api.currentUser().username)
    }

    @Test
    fun `an expired session without stored credentials says so`() = runBlocking {
        val bare = HtmlYamtrackApi(YamtrackSession(url!!, MemoryCookies(), { null }))
        try {
            bare.currentUser()
            fail("expected SessionExpired")
        } catch (e: ApiError.SessionExpired) {
            // expected
        }
    }

    @Test
    fun `create, update, rewatch and delete an anime entry`() = runBlocking {
        api.login(user!!, password!!)
        // Bocchi the Rock!: unlikely to be in a throwaway library already.
        val key = MediaKey("mal", MediaType.ANIME, "47917")
        api.trackForm(key).instanceId?.let { api.deleteEntry(key.type, it) }

        val created = api.saveEntry(key, null, YamtrackEntryFields(LibraryStatus.CURRENT, score = 8.5, progress = 3, notes = "first"))
        val id = assertNotNull(created.instanceId).let { created.instanceId!! }
        assertEquals(3, created.fields.progress)
        assertEquals("first", created.fields.notes)

        val updated = api.saveEntry(key, id, created.fields.copy(progress = 4, score = 9.0))
        assertEquals(id, updated.instanceId)
        assertEquals(4, updated.fields.progress)
        assertEquals(9.0, updated.fields.score!!, 0.0)

        api.stepProgress(key.type, id, increase = true)
        assertEquals(5, api.trackForm(key, id).fields.progress)

        assertEquals(7.5, api.setScore(key.type, id, 7.5)!!, 0.0)

        val rewatch = api.saveEntry(key, null, YamtrackEntryFields(LibraryStatus.PLANNING))
        assertNotEquals(id, rewatch.instanceId)
        val rows = api.libraryEntries().filter { it.key == key }
        assertEquals(2, rows.size)

        api.deleteEntry(key.type, rewatch.instanceId!!)
        api.deleteEntry(key.type, id)
        assertEquals(null, api.trackForm(key).instanceId)
    }

    @Test
    fun `an out of range score is clamped before it is sent`() = runBlocking {
        api.login(user!!, password!!)
        val key = MediaKey("mal", MediaType.ANIME, "47917")
        try {
            val form = api.saveEntry(key, api.trackForm(key).instanceId, YamtrackEntryFields(LibraryStatus.PLANNING))
            val saved = api.saveEntry(key, form.instanceId, form.fields.copy(score = 42.0))
            assertEquals(10.0, saved.fields.score!!, 0.0)
        } finally {
            api.trackForm(key).instanceId?.let { api.deleteEntry(key.type, it) }
        }
    }

    @Test
    fun `a game keeps its play time`() = runBlocking {
        api.login(user!!, password!!)
        val key = MediaKey("igdb", MediaType.GAME, "1942") // The Witcher 3
        api.trackForm(key).instanceId?.let { api.deleteEntry(key.type, it) }
        val saved = api.saveEntry(key, null, YamtrackEntryFields(LibraryStatus.CURRENT, progress = 125))
        assertEquals(125, saved.fields.progress)
        api.deleteEntry(key.type, saved.instanceId!!)
    }

    @Test
    fun `search, details and the library read`() = runBlocking {
        api.login(user!!, password!!)
        val page = api.search(MediaType.MOVIE, "arrival")
        assertTrue(page.results.isNotEmpty())
        val details = api.details(page.results.first().key)
        assertTrue(details.title.isNotBlank())
        assertTrue(api.libraryEntries().isNotEmpty())
        api.inProgressItems()
        Unit
    }
}
