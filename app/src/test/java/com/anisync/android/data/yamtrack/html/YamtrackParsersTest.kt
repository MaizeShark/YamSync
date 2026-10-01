package com.anisync.android.data.yamtrack.html

import com.anisync.android.data.util.ApiError
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/**
 * Parsers against pages captured from a real Yamtrack (v0.26.3). When Yamtrack changes a template,
 * recapture the page and see which expectation breaks.
 */
class YamtrackParsersTest {

    private fun page(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("yamtrack/$name")) { "missing fixture $name" }.readText()

    private val frieren = MediaKey("mal", MediaType.ANIME, "52991")

    // ── Small pieces ──

    @Test
    fun `progress reads numbers, durations and clock times`() {
        assertEquals(5, YamtrackParsers.parseProgress("5"))
        assertEquals(750, YamtrackParsers.parseProgress("12h 30min"))
        assertEquals(45, YamtrackParsers.parseProgress("45min"))
        assertEquals(180, YamtrackParsers.parseProgress("3h"))
        assertEquals(90, YamtrackParsers.parseProgress("1:30"))
        assertNull(YamtrackParsers.parseProgress(""))
        assertNull(YamtrackParsers.parseProgress("soon"))
    }

    @Test
    fun `minutes format the way the duration field reads them`() {
        assertEquals("12h 30min", YamtrackParsers.formatMinutes(750))
        assertEquals(750, YamtrackParsers.parseProgress(YamtrackParsers.formatMinutes(750)))
    }

    @Test
    fun `statuses round trip`() {
        listOf(
            LibraryStatus.CURRENT, LibraryStatus.PLANNING, LibraryStatus.COMPLETED,
            LibraryStatus.PAUSED, LibraryStatus.DROPPED
        ).forEach { status ->
            assertEquals(status, YamtrackParsers.statusFromYamtrack(YamtrackParsers.statusToYamtrack(status)))
        }
    }

    @Test
    fun `detail links become keys, seasons included, under any base path`() {
        assertEquals(frieren, YamtrackParsers.mediaKeyFromHref("/details/mal/anime/52991/sousou-no-frieren"))
        assertEquals(
            MediaKey("tmdb", MediaType.SEASON, "95396", seasonNumber = 1),
            YamtrackParsers.mediaKeyFromHref("/details/tmdb/tv/95396/severance/season/1")
        )
        assertEquals(
            MediaKey("igdb", MediaType.GAME, "113112"),
            YamtrackParsers.mediaKeyFromHref("/yamtrack/details/igdb/game/113112/hades")
        )
        assertNull(YamtrackParsers.mediaKeyFromHref("/lists"))
    }

    @Test
    fun `form dates read and write in the user's zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val millis = YamtrackParsers.parseFormDate("2026-10-01T15:16", zone)!!
        assertEquals("2026-10-01T15:16", YamtrackParsers.formatFormDate(millis, withTime = true, zone = zone))
        assertEquals("2026-10-01", YamtrackParsers.formatFormDate(millis, withTime = false, zone = zone))
    }

    @Test
    fun `csv handles quotes, commas and line breaks inside fields`() {
        val rows = YamtrackParsers.parseCsvRows("\"a\",\"b, c\",\"say \"\"hi\"\"\"\n\"line\nbreak\",\"\",\"x\"\n")
        assertEquals(listOf("a", "b, c", "say \"hi\""), rows[0])
        assertEquals(listOf("line\nbreak", "", "x"), rows[1])
    }

    // ── Pages ──

    @Test
    fun `the export has every row, rewatches separately`() {
        val entries = YamtrackParsers.parseExport(page("export.csv"))
        assertEquals(9, entries.size)

        val frierenRows = entries.filter { it.key == frieren }
        assertEquals(2, frierenRows.size)
        assertEquals(setOf(LibraryStatus.CURRENT, LibraryStatus.COMPLETED), frierenRows.map { it.status }.toSet())

        val game = entries.single { it.key.type == MediaType.GAME }
        assertEquals(750, game.progress)
        assertEquals(LibraryStatus.CURRENT, game.status)

        val movie = entries.single { it.key.type == MediaType.MOVIE }
        assertEquals(8.5, movie.score!!, 0.0)
        assertNotNull(movie.createdAt)

        val episode = entries.single { it.key.type == MediaType.EPISODE }
        assertEquals(1, episode.key.seasonNumber)
        assertEquals(1, episode.key.episodeNumber)
        assertNull(episode.status)
    }

    @Test
    fun `the home page has in-progress items with ids and totals`() {
        val items = YamtrackParsers.parseHomeItems(page("home.html"), "in-progress")
        val anime = items.single { it.key == frieren }
        assertEquals(1L, anime.instanceId)
        assertEquals(28, anime.maxProgress)
        assertEquals(5, anime.progress)
        assertTrue(anime.imageUrl!!.startsWith("https://cdn.myanimelist.net"))

        val game = items.single { it.key.type == MediaType.GAME }
        assertEquals(750, game.progress)
        assertNull(game.maxProgress)

        assertTrue("planning items are a different section", items.none { it.key.type == MediaType.MANGA })
    }

    @Test
    fun `the tracking form gives the entry and its fields`() {
        val form = YamtrackParsers.parseTrackForm(page("track_modal_existing_anime.html"), frieren)
        assertEquals(1L, form.instanceId)
        assertEquals("Sousou no Frieren", form.title)
        assertEquals(LibraryStatus.CURRENT, form.fields.status)
        assertEquals(5, form.fields.progress)
        assertEquals(9.0, form.fields.score!!, 0.0)
        assertTrue(form.datesHaveTime)
        assertTrue("progress" in form.availableFields)
    }

    @Test
    fun `a movie form has no progress`() {
        val form = YamtrackParsers.parseTrackForm(
            page("track_modal_existing_movie.html"),
            MediaKey("tmdb", MediaType.MOVIE, "438631")
        )
        assertTrue("progress" !in form.availableFields)
        assertEquals(LibraryStatus.COMPLETED, form.fields.status)
    }

    @Test
    fun `a game form reads its play time as minutes`() {
        val form = YamtrackParsers.parseTrackForm(
            page("track_modal_existing_game.html"),
            MediaKey("igdb", MediaType.GAME, "113112")
        )
        assertEquals(750, form.fields.progress)
    }

    @Test
    fun `a form for an untracked item has no instance`() {
        val form = YamtrackParsers.parseTrackForm(
            page("track_modal_new_manga.html"),
            MediaKey("mal", MediaType.MANGA, "2")
        )
        assertNull(form.instanceId)
    }

    @Test
    fun `search results have keys, titles, images and paging`() {
        val result = YamtrackParsers.parseSearch(page("search_anime.html"))
        assertEquals(1, result.page)
        assertEquals(4, result.totalPages)
        assertEquals(72, result.totalResults)
        assertTrue(result.results.size in 1..24)
        val first = result.results.first()
        assertEquals(MediaKey("mal", MediaType.ANIME, "59978"), first.key)
        assertEquals("Sousou no Frieren 2nd Season", first.title)
        assertTrue(first.imageUrl!!.startsWith("https://"))
        assertEquals(result.results.size, result.results.map { it.key }.distinct().size)
    }

    @Test
    fun `a tracked search result carries its entry`() {
        val result = YamtrackParsers.parseSearch(page("search_movie.html"))
        val dune = result.results.first { it.key.mediaId == "438631" }
        assertNotNull(dune.tracked)
        assertEquals(1L, dune.tracked!!.instanceId)
    }

    @Test
    fun `anime details`() {
        val details = YamtrackParsers.parseDetails(page("details_anime.html"), frieren)
        assertEquals("Sousou no Frieren", details.title)
        assertTrue(details.synopsis!!.length > 100)
        assertTrue(details.genres.isNotEmpty())
        assertNotNull(details.score)
        assertTrue(details.info.isNotEmpty())
        assertTrue(details.sourceUrl!!.contains("myanimelist.net"))
        assertTrue(details.imageUrl!!.startsWith("https://"))
        assertTrue(details.related.all { section -> section.items.isNotEmpty() })
    }

    @Test
    fun `tv details list seasons`() {
        val details = YamtrackParsers.parseDetails(page("details_tv.html"), MediaKey("tmdb", MediaType.TV, "95396"))
        assertEquals("Severance", details.title)
        assertTrue(details.seasons.any { it.number == 1 })
        assertTrue("seasons are not repeated as a related section", details.related.none { it.title == "Seasons" })
    }

    @Test
    fun `movie details list the cast with their roles`() {
        val details = YamtrackParsers.parseDetails(page("details_movie.html"), MediaKey("tmdb", MediaType.MOVIE, "438631"))
        assertEquals("Dune", details.title)
        assertTrue(details.cast.isNotEmpty())
        assertNotNull(details.cast.first().role)
    }

    @Test
    fun `season details list episodes and which were watched`() {
        val key = MediaKey("tmdb", MediaType.SEASON, "95396", seasonNumber = 1)
        val details = YamtrackParsers.parseDetails(page("season_details_tv.html"), key)
        assertEquals("Severance", details.title)
        assertNotNull(details.seasonTitle)
        assertTrue(details.episodes.size >= 9)
        assertEquals(1, details.episodes.first().number)
        assertEquals(1, details.episodes.first().watchCount)
        assertEquals(0, details.episodes[1].watchCount)
    }

    @Test
    fun `account and token`() {
        assertEquals("demo", YamtrackParsers.parseUsername(page("account.html")))
        assertEquals("nu0xav4EKHiOOOspxe-Rxv7vxyDGNhjs", YamtrackParsers.parseToken(page("integrations.html")))
    }

    @Test
    fun `lists`() {
        val lists = YamtrackParsers.parseLists(page("lists.html"))
        assertEquals(1, lists.size)
        assertEquals(1L, lists.first().id)
        assertEquals("Favourites", lists.first().name)
    }

    @Test
    fun `ics events unfold and keep utc times`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:42\r\nSUMMARY:Severance E5\r\n" +
            "DTSTART:20261005T010000Z\r\nDTEND:20261005T010000Z\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\n" +
            "UID:43\r\nSUMMARY:A very long title\\, folded\r\n  over lines E1\r\nDTSTART;VALUE=DATE-TIME:20261006T120000Z\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val events = YamtrackParsers.parseIcs(ics)
        assertEquals(2, events.size)
        assertEquals("Severance E5", events[0].summary)
        assertEquals(java.time.Instant.parse("2026-10-05T01:00:00Z").toEpochMilli(), events[0].startsAt)
        assertEquals("A very long title, folded over lines E1", events[1].summary)
    }

    @Test
    fun `calendar events join to their items by summary`() {
        val events = listOf(YamtrackParsers.IcsEvent("1", "Severance E5", 0L))
        val links = listOf(
            YamtrackParsers.CalendarLink("Severance E5", MediaKey("tmdb", MediaType.SEASON, "95396", 2), null)
        )
        val joined = YamtrackParsers.joinCalendar(events, links).single()
        assertEquals(5, joined.contentNumber)
        assertEquals("Severance", joined.title)
        assertEquals(2, joined.key!!.seasonNumber)
    }

    @Test
    fun `a page without its form is reported, not read as empty`() {
        try {
            YamtrackParsers.parseTrackForm(page("lists.html"), frieren)
            fail("expected a parse error")
        } catch (e: ApiError.ParseError) {
            assertTrue(e.message!!.contains("tracking form"))
        }
    }
}
