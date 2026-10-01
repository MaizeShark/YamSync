package com.anisync.android.data.yamtrack.html

import com.anisync.android.data.util.ApiError
import com.anisync.android.data.yamtrack.YamtrackCalendarEvent
import com.anisync.android.data.yamtrack.YamtrackCastMember
import com.anisync.android.data.yamtrack.YamtrackCustomList
import com.anisync.android.data.yamtrack.YamtrackEntry
import com.anisync.android.data.yamtrack.YamtrackEntryFields
import com.anisync.android.data.yamtrack.YamtrackEpisode
import com.anisync.android.data.yamtrack.YamtrackHomeItem
import com.anisync.android.data.yamtrack.YamtrackMediaDetails
import com.anisync.android.data.yamtrack.YamtrackRelatedSection
import com.anisync.android.data.yamtrack.YamtrackSearchPage
import com.anisync.android.data.yamtrack.YamtrackSearchResult
import com.anisync.android.data.yamtrack.YamtrackSeasonRef
import com.anisync.android.data.yamtrack.YamtrackStreamingProvider
import com.anisync.android.data.yamtrack.YamtrackTrackForm
import com.anisync.android.data.yamtrack.YamtrackTrackedSummary
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Reads Yamtrack's pages.
 *
 * Every function here takes the text the server sent and returns app types, with no I/O, so each one
 * is tested against pages captured from a real server. Selectors lean on what the page means rather
 * than how it is styled: links to `/details/…`, form field names, section headings and element ids
 * Yamtrack's own HTMX code depends on. Utility classes are only used where nothing else identifies
 * an element.
 *
 * A page that lacks what makes it that page throws [ApiError.ParseError], so a changed template is
 * reported instead of reading as an empty library.
 */
object YamtrackParsers {

    // ── Statuses, numbers, dates ────────────────────────────────────────────────────────────────

    /** Yamtrack's status values, as its forms and export spell them. */
    fun statusFromYamtrack(value: String?): LibraryStatus? = when (value?.trim()) {
        "In progress" -> LibraryStatus.CURRENT
        "Planning" -> LibraryStatus.PLANNING
        "Completed" -> LibraryStatus.COMPLETED
        "Paused" -> LibraryStatus.PAUSED
        "Dropped" -> LibraryStatus.DROPPED
        else -> null
    }

    fun statusToYamtrack(status: LibraryStatus): String = when (status) {
        LibraryStatus.CURRENT, LibraryStatus.REPEATING -> "In progress"
        LibraryStatus.PLANNING -> "Planning"
        LibraryStatus.COMPLETED -> "Completed"
        LibraryStatus.PAUSED -> "Paused"
        LibraryStatus.DROPPED -> "Dropped"
        LibraryStatus.UNKNOWN -> "Planning"
    }

    private val durationPattern = Regex("""^\s*(?:(\d+)\s*h)?\s*(?:(\d+)\s*min)?\s*$""")
    private val clockPattern = Regex("""^\s*(\d+):(\d{1,2})\s*$""")

    /**
     * Progress as Yamtrack writes it. Plain numbers for most types; games use play time, which the
     * export and forms write as `12h 30min` and the form also accepts as `hh:mm`.
     */
    fun parseProgress(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it }
        clockPattern.matchEntire(text)?.let { m ->
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
        durationPattern.matchEntire(text)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull()
            val minutes = m.groupValues[2].toIntOrNull()
            if (hours == null && minutes == null) return null
            return (hours ?: 0) * 60 + (minutes ?: 0)
        }
        return null
    }

    /** The inverse of [parseProgress] for games, in the form Yamtrack's own duration field takes. */
    fun formatMinutes(minutes: Int): String = "${minutes / 60}h ${minutes % 60}min"

    private val exportDateTime: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .appendPattern("XXX")
        .toFormatter()

    /** A timestamp from the CSV export: `2026-10-01 13:16:56.101843+00:00`, or a bare date. */
    fun parseExportTimestamp(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(text, exportDateTime).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /** A form date: `datetime-local` (`2026-10-01T15:16`) or a plain date, in the user's zone. */
    fun parseFormDate(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    fun formatFormDate(epochMillis: Long, withTime: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val dateTime = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        return if (withTime) {
            dateTime.toLocalDateTime().withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        } else {
            dateTime.toLocalDate().toString()
        }
    }

    // ── Links ───────────────────────────────────────────────────────────────────────────────────

    private val detailsPath = Regex("""/details/([^/]+)/([^/]+)/([^/?#]+)(?:/[^/?#]*)?(?:/season/(\d+))?""")

    /**
     * The item a `/details/…` link points at. Works with any `BASE_URL` prefix in front. A TV link
     * with `/season/n` is that season.
     */
    fun mediaKeyFromHref(href: String?): MediaKey? {
        val match = href?.let { detailsPath.find(it) } ?: return null
        val (source, typeSlug, mediaId) = match.destructured
        val season = match.groupValues[4].toIntOrNull()
        val type = MediaType.fromSlug(typeSlug) ?: return null
        return if (season != null && type == MediaType.TV) {
            MediaKey(source, MediaType.SEASON, mediaId, seasonNumber = season)
        } else {
            MediaKey(source, type, mediaId)
        }
    }

    /** The real image of a lazily loaded `<img>`, which keeps its placeholder in `src`. */
    private fun Element.imageUrl(): String? =
        (attr("data-src").ifBlank { attr("src") }).ifBlank { null }?.takeUnless { it.isPlaceholder() }

    /** Yamtrack's "no image" placeholder, which reads better as no image at all. */
    private fun String.isPlaceholder(): Boolean = contains("glyphicons-basic-38-picture")

    // ── Session pages ───────────────────────────────────────────────────────────────────────────

    /** The username from the account settings page. */
    fun parseUsername(html: String): String =
        Jsoup.parse(html).selectFirst("input[name=username]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: throw ApiError.ParseError("account settings", "no username field")

    /** The calendar/webhook token from the integrations page, or null when none is set. */
    fun parseToken(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("a[href*=/calendar/download/], input[value*=/calendar/download/]").forEach { el ->
            val text = el.attr("href").ifBlank { el.attr("value") }
            Regex("""/calendar/download/([^/"?\s]+)""").find(text)?.let { return it.groupValues[1] }
        }
        // The integrations page lists the token in a read-only field next to the webhook URLs.
        return doc.select("input[readonly]").map { it.attr("value") }
            .firstOrNull { it.matches(Regex("[A-Za-z0-9_-]{16,}")) }
    }

    /** Toasts Django queued for the next page, as (level, text). Rendered by every full page. */
    fun parseMessages(html: String): List<Pair<String, String>> =
        Jsoup.parse(html).select("[class*=toast-]").mapNotNull { toast ->
            val level = toast.classNames().firstOrNull { it.startsWith("toast-") }?.removePrefix("toast-")
                ?: return@mapNotNull null
            val text = toast.selectFirst("p")?.text()?.trim().orEmpty()
            if (text.isEmpty()) null else level to text
        }

    // ── Library ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The CSV export: one row per tracked entry of every type. Columns are found by header name,
     * so reordered or added columns in later versions do not break it.
     */
    fun parseExport(csv: String): List<YamtrackEntry> {
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) throw ApiError.ParseError("export", "empty file")
        val header = rows.first()
        fun index(name: String) = header.indexOf(name)
        val iMediaId = index("media_id")
        val iSource = index("source")
        val iType = index("media_type")
        if (iMediaId < 0 || iSource < 0 || iType < 0) {
            throw ApiError.ParseError("export", "missing item columns in ${header.take(8)}")
        }
        val iTitle = index("title")
        val iImage = index("image")
        val iSeason = index("season_number")
        val iEpisode = index("episode_number")
        val iScore = index("score")
        val iStatus = index("status")
        val iNotes = index("notes")
        val iStart = index("start_date")
        val iEnd = index("end_date")
        val iProgress = index("progress")
        val iCreated = index("created_at")
        val iProgressed = index("progressed_at")
        fun List<String>.at(i: Int): String? = if (i >= 0) getOrNull(i)?.takeIf { it.isNotEmpty() } else null

        return rows.drop(1).mapNotNull { row ->
            val type = MediaType.fromSlug(row.at(iType) ?: return@mapNotNull null) ?: return@mapNotNull null
            YamtrackEntry(
                key = MediaKey(
                    source = row.at(iSource) ?: return@mapNotNull null,
                    type = type,
                    mediaId = row.at(iMediaId) ?: return@mapNotNull null,
                    seasonNumber = row.at(iSeason)?.toIntOrNull(),
                    episodeNumber = row.at(iEpisode)?.toIntOrNull()
                ),
                title = row.at(iTitle).orEmpty(),
                imageUrl = row.at(iImage)?.takeUnless { it.isPlaceholder() },
                status = statusFromYamtrack(row.at(iStatus)),
                score = row.at(iScore)?.toDoubleOrNull(),
                progress = parseProgress(row.at(iProgress)),
                startDate = parseExportTimestamp(row.at(iStart)),
                endDate = parseExportTimestamp(row.at(iEnd)),
                notes = row.at(iNotes),
                createdAt = parseExportTimestamp(row.at(iCreated)),
                progressedAt = parseExportTimestamp(row.at(iProgressed))
            )
        }
    }

    /** RFC 4180 CSV: quoted fields, doubled quotes inside them, line breaks inside quotes. */
    internal fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotEmpty() } || row.size > 1) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private val progressPattern = Regex("""^\s*([\dhmin :]+?)\s*(?:/\s*(\d+))?\s*(?:[A-Za-z]+)?\s*$""")

    /**
     * Home page cards, or the fragment its "Load all" button fetches. Only cards inside a section
     * whose grid id names [section] (`in-progress` or `planning`) are read.
     */
    fun parseHomeItems(html: String, section: String = "in-progress"): List<YamtrackHomeItem> {
        val doc = Jsoup.parse(html)
        val grids = doc.select("div[id^=media-grid-$section-]")
        // A "Load all" fragment is just cards, without the surrounding grid.
        val cards = if (grids.isEmpty()) doc.select("div[id^=home-media-]") else grids.select("div[id^=home-media-]")
        return cards.mapNotNull { card -> parseHomeCard(card) }
    }

    private fun parseHomeCard(card: Element): YamtrackHomeItem? {
        val link = card.selectFirst("a[href*=/details/]") ?: return null
        val key = mediaKeyFromHref(link.attr("href")) ?: return null
        val instanceId = card.id().substringAfterLast('-').toLongOrNull() ?: return null
        val image = card.selectFirst("img")?.imageUrl()
        val title = link.attr("title").ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
        val progressText = card.selectFirst("div[id^=progress-] div.text-xs")?.text()
            ?: card.selectFirst("div[id^=progress-]")?.text()
        var progress: Int? = null
        var max: Int? = null
        progressText?.let { text ->
            val slash = text.indexOf('/')
            val before = if (slash >= 0) text.substring(0, slash) else text
            progress = parseProgress(before.replace(Regex("[A-Za-z]+s?$"), "").trim())
                ?: parseProgress(before.trim())
            if (slash >= 0) max = Regex("""\d+""").find(text.substring(slash))?.value?.toIntOrNull()
        }
        return YamtrackHomeItem(key, instanceId, title, image, progress, max)
    }

    /** "Load all" URLs on the home page for [section], to fetch the cards beyond the first few. */
    fun parseHomeLoadMore(html: String, section: String = "in-progress"): List<String> {
        val status = when (section) {
            "in-progress" -> "In progress"
            else -> "Planning"
        }
        return Jsoup.parse(html).select("button[hx-get*=load_media_type]")
            .map { it.attr("hx-get") }
            .filter { url ->
                val param = Regex("""load_status=([^&]+)""").find(url)?.groupValues?.get(1)
                java.net.URLDecoder.decode(param ?: "", "UTF-8") == status
            }
    }

    // ── Tracking form ───────────────────────────────────────────────────────────────────────────

    /** The `track_modal` fragment: the edit form for one item. */
    fun parseTrackForm(html: String, key: MediaKey): YamtrackTrackForm {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form:has(select[name=status])")
            ?: throw ApiError.ParseError("tracking form", "no status field")
        fun value(name: String): String? {
            val el = form.selectFirst("[name=$name]") ?: return null
            return when (el.tagName()) {
                "select" -> el.selectFirst("option[selected]")?.attr("value")
                // HTML drops the newline right after <textarea>; jsoup keeps it.
                "textarea" -> el.wholeText().removePrefix("\r").removePrefix("\n")
                else -> el.attr("value")
            }?.takeIf { it.isNotBlank() }
        }
        val fieldNames = form.select("[name]").map { it.attr("name") }.toSet()
        val dateField = form.selectFirst("input[name=start_date], input[name=end_date]")
        val status = statusFromYamtrack(value("status"))
            ?: statusFromYamtrack(form.selectFirst("select[name=status] option")?.attr("value"))
            ?: LibraryStatus.PLANNING
        return YamtrackTrackForm(
            key = key,
            instanceId = value("instance_id")?.toLongOrNull(),
            title = doc.selectFirst("h2")?.text()?.trim(),
            fields = YamtrackEntryFields(
                status = status,
                score = value("score")?.toDoubleOrNull(),
                progress = parseProgress(value("progress")),
                startDate = parseFormDate(value("start_date")),
                endDate = parseFormDate(value("end_date")),
                notes = value("notes")
            ),
            availableFields = fieldNames,
            datesHaveTime = dateField?.attr("type") != "date"
        )
    }

    // ── Search and details ──────────────────────────────────────────────────────────────────────

    /** A card or list row for one item, wherever Yamtrack shows it (search, related, lists). */
    private fun parseMediaCard(card: Element): YamtrackSearchResult? {
        val link = card.selectFirst("a[href*=/details/]") ?: return null
        val key = mediaKeyFromHref(link.attr("href")) ?: return null
        val img = card.selectFirst("img")
        val title = card.select("a[href*=/details/][title]").firstOrNull()?.attr("title")
            ?: img?.attr("alt")
            ?: link.text()
        val instanceId = card.select("[hx-vals*=instance_id]").firstNotNullOfOrNull { el ->
            Regex(""""instance_id":\s*"(\d+)"""").find(el.attr("hx-vals"))?.groupValues?.get(1)?.toLongOrNull()
        }
        val score = card.selectFirst("svg.text-yellow-400 + span, span:matchesOwn(^\\d+(\\.\\d)?$)")?.text()?.toDoubleOrNull()
        val progressText = card.select("span").map { it.text().trim() }.firstOrNull { it.contains('/') && it.any(Char::isDigit) }
        val tracked = if (instanceId != null) {
            YamtrackTrackedSummary(
                instanceId = instanceId,
                score = score,
                progress = progressText?.substringBefore('/')?.let(::parseProgress),
                maxProgress = progressText?.substringAfter('/')?.trim()?.toIntOrNull()
            )
        } else {
            null
        }
        return YamtrackSearchResult(key, title.trim(), img?.imageUrl(), tracked)
    }

    /**
     * The cards of a grid, one per distinct item. A card links to its item more than once (image
     * and title), and nested elements match too, so this keeps the outermost element per link.
     */
    private fun Element.mediaCards(): List<Element> {
        val seen = LinkedHashMap<String, Element>()
        for (link in select("a[href*=/details/]")) {
            val href = link.attr("href")
            if (href in seen) continue
            // The card is the nearest ancestor that also holds the title link of this item.
            var card: Element? = link.parent()
            while (card != null && card != this && card.select("a[href=\"$href\"]").size < 2 &&
                card.selectFirst("img") == null
            ) {
                card = card.parent()
            }
            var container = card ?: link
            // Climb to the card root: the outermost ancestor whose item links all point here.
            while (true) {
                val parent = container.parent() ?: break
                if (parent == this) break
                val hrefs = parent.select("a[href*=/details/]").map { it.attr("href") }.toSet()
                if (hrefs.size != 1) break
                container = parent
            }
            seen[href] = container
        }
        return seen.values.toList()
    }

    fun parseSearch(html: String): YamtrackSearchPage {
        val doc = Jsoup.parse(html)
        val main = doc.selectFirst("h2:matchesOwn(^Search Results$)")?.parent()?.parent() ?: doc.body()
        val results = main.mediaCards().mapNotNull(::parseMediaCard)
        val pageInfo = doc.select("div").map { it.ownText() }
            .firstNotNullOfOrNull { Regex("""Page (\d+) of (\d+) \((\d+) results?\)""").find(it) }
        if (results.isEmpty() && pageInfo == null && !html.contains("No results found")) {
            if (doc.selectFirst("h2:matchesOwn(^Search Results$)") == null) {
                throw ApiError.ParseError("search", "no results section")
            }
        }
        return YamtrackSearchPage(
            page = pageInfo?.groupValues?.get(1)?.toInt() ?: 1,
            totalPages = pageInfo?.groupValues?.get(2)?.toInt() ?: 1,
            totalResults = pageInfo?.groupValues?.get(3)?.toInt() ?: results.size,
            results = results
        )
    }

    fun parseDetails(html: String, key: MediaKey): YamtrackMediaDetails {
        val doc = Jsoup.parse(html)
        // The page's first <h1> is the site name in the navigation; the media title is the one
        // that shares the hero block with the synopsis.
        val synopsisEl = doc.selectFirst("p[x-ref=synopsisText]")
            ?: throw ApiError.ParseError("details", "no synopsis block")
        val hero = synopsisEl.parents().firstOrNull { it.selectFirst("h1") != null }
            ?: throw ApiError.ParseError("details", "no title")
        val h1 = hero.selectFirst("h1")!!
        val isSeason = key.type == MediaType.SEASON
        val showTitle = if (isSeason) hero.selectFirst("h2 a[href*=/details/]")?.text() else null
        val title = showTitle ?: h1.text()

        val genres = hero.select("span.rounded-full").map { it.text().trim() }.filter { it.isNotEmpty() }
        val synopsis = hero.selectFirst("p[x-ref=synopsisText]")?.let { p ->
            p.select("br").forEach { it.after("\n") }
            p.wholeText().lines().joinToString("\n") { it.trim() }.trim().ifEmpty { null }
        }
        val scoreBox = doc.select("h3").firstOrNull { it.text().endsWith("SCORE") && !it.text().startsWith("YOUR") }
            ?.parent()
        val score = scoreBox?.selectFirst("span.text-lg")?.text()?.toDoubleOrNull()
        val scoreCount = scoreBox?.selectFirst("p")?.text()?.let { Regex("""[\d,.]+""").find(it)?.value }
            ?.replace(",", "")?.replace(".", "")?.toIntOrNull()

        val info = mutableListOf<Pair<String, List<String>>>()
        var sourceUrl: String? = null
        val links = mutableListOf<Pair<String, String>>()
        doc.selectFirst("h2:matchesOwn(^Details$)")?.nextElementSibling()?.children()?.forEach { row ->
            val label = row.selectFirst("h3")?.text()?.trim() ?: return@forEach
            when (label.lowercase()) {
                "provider" -> sourceUrl = row.selectFirst("a")?.attr("href")
                "links" -> row.select("a[href]").forEach { links.add(it.text().trim() to it.attr("href")) }
                else -> info.add(label to row.select("p").map { it.text().trim() }.filter { it.isNotEmpty() })
            }
        }

        val cast = doc.selectFirst("h2:matchesOwn(^Cast$)")?.nextElementSibling()
            ?.select("div.shrink-0")?.mapNotNull { card ->
                val name = card.selectFirst("div.text-sm")?.text()?.trim() ?: return@mapNotNull null
                YamtrackCastMember(
                    name = name,
                    role = card.selectFirst("div.text-xs")?.text()?.trim()?.ifEmpty { null },
                    imageUrl = card.selectFirst("img")?.imageUrl()
                )
            }.orEmpty()

        val related = doc.select("section").mapNotNull { section ->
            val heading = section.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            if (heading == "Cast" || heading == "Streaming" || heading.startsWith("Repeats")) return@mapNotNull null
            val grid = section.selectFirst("div.grid") ?: return@mapNotNull null
            val items = grid.mediaCards().mapNotNull(::parseMediaCard)
            if (items.isEmpty()) null else YamtrackRelatedSection(heading, items)
        }
        val seasons = if (key.type == MediaType.TV) {
            related.firstOrNull { it.title.equals("Seasons", ignoreCase = true) }?.items
                ?.mapNotNull { item -> item.key.seasonNumber?.let { YamtrackSeasonRef(it, item.title) } }
                .orEmpty()
        } else {
            doc.select("a[href*=/season/]").mapNotNull { a ->
                val number = mediaKeyFromHref(a.attr("href"))?.seasonNumber ?: return@mapNotNull null
                val label = a.selectFirst("div.text-sm")?.text()?.trim() ?: return@mapNotNull null
                YamtrackSeasonRef(number, label)
            }.distinctBy { it.number }
        }

        val episodes = doc.selectFirst("h2:matchesOwn(^Episodes$)")?.nextElementSibling()
            ?.children()?.mapNotNull { card ->
                val meta = card.selectFirst("p.text-sm.text-gray-400")?.text() ?: return@mapNotNull null
                val number = Regex("""Episode (\d+)""").find(meta)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val parts = meta.split('•').map { it.trim() }
                val history = card.select("p").map { it.text() }.firstOrNull { it.startsWith("Last watched") }
                val watchCount = when {
                    history == null -> 0
                    else -> Regex("""Watched (\d+) times""").find(history)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                }
                YamtrackEpisode(
                    number = number,
                    title = card.selectFirst("h2")?.text()?.trim()?.ifEmpty { null },
                    airDate = parts.getOrNull(1)?.takeUnless { it == "Unknown air date" },
                    runtime = parts.getOrNull(2),
                    imageUrl = card.selectFirst("img")?.imageUrl(),
                    overview = card.selectFirst("p.leading-relaxed")?.text()?.trim()?.ifEmpty { null },
                    watchCount = watchCount
                )
            }.orEmpty()

        val providers = doc.selectFirst("h2:matchesOwn(^Streaming$)")?.parent()
            ?.select("div:has(> img)")?.map { row ->
                YamtrackStreamingProvider(
                    name = row.selectFirst("span")?.text()?.trim().orEmpty(),
                    logoUrl = row.selectFirst("img")?.imageUrl()
                )
            }?.filter { it.name.isNotEmpty() }.orEmpty()

        return YamtrackMediaDetails(
            key = key,
            title = title,
            seasonTitle = if (isSeason) h1.text().trim() else null,
            imageUrl = doc.selectFirst("img[alt]:not(.lazyload)")?.imageUrl(),
            synopsis = synopsis,
            genres = genres,
            score = score,
            scoreCount = scoreCount,
            info = info,
            sourceUrl = sourceUrl,
            externalLinks = links,
            cast = cast,
            related = related.filterNot { key.type == MediaType.TV && it.title.equals("Seasons", ignoreCase = true) },
            seasons = seasons,
            episodes = episodes,
            streamingProviders = providers
        )
    }

    // ── Calendar ────────────────────────────────────────────────────────────────────────────────

    /** One VEVENT of the feed: uid, summary and start instant. */
    data class IcsEvent(val uid: String, val summary: String, val startsAt: Long)

    /** Yamtrack's iCal feed. Folded lines are unfolded and escapes undone per RFC 5545. */
    fun parseIcs(text: String): List<IcsEvent> {
        if (!text.contains("BEGIN:VCALENDAR")) throw ApiError.ParseError("calendar feed", "not iCalendar")
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").lines()
        val events = mutableListOf<IcsEvent>()
        var fields: MutableMap<String, String>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> fields = mutableMapOf()
                line == "END:VEVENT" -> {
                    val f = fields ?: continue
                    val start = f["DTSTART"]?.let(::parseIcsDate)
                    val summary = f["SUMMARY"]
                    if (start != null && summary != null) events.add(IcsEvent(f["UID"].orEmpty(), summary, start))
                    fields = null
                }
                fields != null && ':' in line -> {
                    val name = line.substringBefore(':').substringBefore(';')
                    fields[name] = line.substringAfter(':')
                        .replace("\\,", ",").replace("\\;", ";").replace("\\n", "\n").replace("\\\\", "\\")
                }
            }
        }
        return events
    }

    private fun parseIcsDate(value: String): Long? = runCatching {
        when {
            value.endsWith("Z") -> java.time.Instant.from(
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(value)
            ).toEpochMilli()
            'T' in value -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            else -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        }
    }.getOrNull()

    /** A release on the calendar page: its summary text and the item it links to. */
    data class CalendarLink(val summary: String, val key: MediaKey, val imageUrl: String?)

    /**
     * Item links on the calendar page, in either of its layouts. The page prints the same summary
     * as the feed, followed by the time in the viewer's format, so the time is cut off again.
     */
    fun parseCalendarLinks(html: String): List<CalendarLink> =
        Jsoup.parse(html).select("a[href*=/details/]").mapNotNull { a ->
            val key = mediaKeyFromHref(a.attr("href")) ?: return@mapNotNull null
            val titled = a.selectFirst("[title]")?.attr("title")
            val summary = if (titled != null) {
                titled.replace(Regex("""\s+\d{1,2}:\d{2}(\s?[AaPp]\.?[Mm]\.?)?\s*$"""), "").trim()
            } else {
                val name = a.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val number = a.select("span").map { it.text() }
                    .firstNotNullOfOrNull { Regex("""^(\S+?\d+) at """).find(it)?.groupValues?.get(1) }
                listOfNotNull(name, number).joinToString(" ")
            }
            CalendarLink(summary, key, a.selectFirst("img")?.imageUrl())
        }

    /** Joins feed events to calendar page links on their summary text. */
    fun joinCalendar(events: List<IcsEvent>, links: List<CalendarLink>): List<YamtrackCalendarEvent> {
        val bySummary = links.groupBy { it.summary }
        return events.map { event ->
            val link = bySummary[event.summary]?.firstOrNull()
            val number = Regex("""\s(?:E|#|P)(\d+)$""").find(event.summary)?.groupValues?.get(1)?.toIntOrNull()
            YamtrackCalendarEvent(
                uid = event.uid,
                summary = event.summary,
                startsAt = event.startsAt,
                key = link?.key,
                title = number?.let { event.summary.substringBeforeLast(' ') } ?: event.summary,
                contentNumber = number,
                imageUrl = link?.imageUrl
            )
        }
    }

    // ── Lists ───────────────────────────────────────────────────────────────────────────────────

    fun parseLists(html: String): List<YamtrackCustomList> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href]").mapNotNull { a ->
            val id = Regex("""/list/(\d+)/?$""").find(a.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            id to a
        }.distinctBy { it.first }.map { (id, a) ->
            val card = a.parents().firstOrNull { parent -> parent.select("h3, h2").isNotEmpty() } ?: a
            val name = card.selectFirst("h3, h2")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt").orEmpty()
            val count = card.select("span, p").map { it.text() }
                .firstNotNullOfOrNull { Regex("""(\d+) items?""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val description = card.select("p").map { it.text().trim() }
                .firstOrNull { it.isNotEmpty() && !Regex("""\d+ items?""").containsMatchIn(it) }
            YamtrackCustomList(id, name, description, count)
        }
    }

    /** Parses one document once for callers that run several of the above over it. */
    fun document(html: String): Document = Jsoup.parse(html)
}
