package com.anisync.android.data.yamtrack.html

import com.anisync.android.data.util.ApiError
import com.anisync.android.data.yamtrack.YamtrackApi
import com.anisync.android.data.yamtrack.YamtrackCalendarEvent
import com.anisync.android.data.yamtrack.YamtrackCustomList
import com.anisync.android.data.yamtrack.YamtrackEntry
import com.anisync.android.data.yamtrack.YamtrackEntryFields
import com.anisync.android.data.yamtrack.YamtrackHomeItem
import com.anisync.android.data.yamtrack.YamtrackMediaDetails
import com.anisync.android.data.yamtrack.YamtrackSearchPage
import com.anisync.android.data.yamtrack.YamtrackTrackForm
import com.anisync.android.data.yamtrack.YamtrackUser
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import com.anisync.android.domain.model.ProgressUnit
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [YamtrackApi] over Yamtrack's web UI: the same pages and forms a browser uses.
 *
 * Reads avoid every request that changes a preference as a side effect. The media list pages store
 * whatever layout, sort and status filter they are asked for, so the library comes from the CSV
 * export instead, which has every row of every type and changes nothing.
 *
 * Writes go through the same form the web page posts. Yamtrack answers a save with a redirect
 * whether or not it accepted the values, and puts any validation error in a toast for the next page.
 * So every save is read back, and only when the values did not stick is a page fetched to collect
 * the reason.
 */
class HtmlYamtrackApi(
    private val session: YamtrackSession
) : YamtrackApi {

    override suspend fun login(username: String, password: String): YamtrackUser {
        session.login(username, password)
        return currentUser()
    }

    override suspend fun currentUser(): YamtrackUser {
        val username = YamtrackParsers.parseUsername(session.get("/settings/account"))
        val token = runCatching { YamtrackParsers.parseToken(session.get("/settings/integrations")) }.getOrNull()
        return YamtrackUser(username, token)
    }

    override suspend fun libraryEntries(): List<YamtrackEntry> =
        YamtrackParsers.parseExport(session.get("/export/csv"))

    override suspend fun inProgressItems(): List<YamtrackHomeItem> {
        val home = session.get("/")
        val first = YamtrackParsers.parseHomeItems(home, "in-progress")
        // The home page shows a handful per type; "Load all" fetches the rest. Its URL carries the
        // sort the user already has, so following it changes nothing.
        val more = YamtrackParsers.parseHomeLoadMore(home, "in-progress").flatMap { url ->
            val path = url.substringBefore('?')
            val query = url.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            YamtrackParsers.parseHomeItems(session.get(path.relativeTo(session), query, htmx = true), "in-progress")
        }
        return (first + more).distinctBy { it.key to it.instanceId }
    }

    override suspend fun trackForm(key: MediaKey, instanceId: Long?): YamtrackTrackForm {
        val html = session.get(
            path = "/track_modal/" + key.formPath(),
            query = mapOf("return_url" to "/", "instance_id" to instanceId?.toString())
        )
        return YamtrackParsers.parseTrackForm(html, key)
    }

    override suspend fun saveEntry(key: MediaKey, instanceId: Long?, fields: YamtrackEntryFields): YamtrackTrackForm {
        // The form says which fields this type has and how dates are written; a new entry gets the
        // blank form (`is_create`), not the newest existing row.
        val template = if (instanceId == null) {
            YamtrackParsers.parseTrackForm(
                session.get(
                    "/track_modal/" + key.formPath(),
                    mapOf("return_url" to "/", "is_create" to "true")
                ),
                key
            )
        } else {
            trackForm(key, instanceId)
        }
        val form = buildMap {
            put("media_id", key.mediaId)
            put("source", key.source)
            put("media_type", key.type.slug)
            key.seasonNumber?.let { put("season_number", it.toString()) }
            instanceId?.let { put("instance_id", it.toString()) }
            put("status", YamtrackParsers.statusToYamtrack(fields.status))
            if ("score" in template.availableFields) put("score", fields.score?.let(::formatScore).orEmpty())
            if ("progress" in template.availableFields) {
                // Yamtrack requires progress, so "none yet" is sent as zero.
                put("progress", formatProgress(key.type, fields.progress ?: 0))
            }
            if ("start_date" in template.availableFields) {
                put("start_date", fields.startDate?.let { YamtrackParsers.formatFormDate(it, template.datesHaveTime) }.orEmpty())
            }
            if ("end_date" in template.availableFields) {
                put("end_date", fields.endDate?.let { YamtrackParsers.formatFormDate(it, template.datesHaveTime) }.orEmpty())
            }
            if ("notes" in template.availableFields) put("notes", fields.notes.orEmpty())
        }
        val result = session.post("/media_save", form)
        if (!result.isRedirect) throw ApiError.Unknown("Saving was answered with ${result.code}")

        val saved = trackForm(key, instanceId)
        if (instanceId == null && saved.instanceId == null) {
            throw rejection("The entry was not created.")
        }
        if (!saved.fields.matches(fields, template)) {
            throw rejection("The server kept different values than the ones sent.")
        }
        return saved
    }

    override suspend fun deleteEntry(type: MediaType, instanceId: Long) {
        val result = session.post("/media_delete", mapOf("instance_id" to instanceId.toString(), "media_type" to type.slug))
        if (!result.isRedirect) throw ApiError.Unknown("Deleting was answered with ${result.code}")
    }

    override suspend fun stepProgress(type: MediaType, instanceId: Long, increase: Boolean) {
        val result = session.post(
            "/progress_edit/${type.slug}/$instanceId",
            mapOf("operation" to if (increase) "increase" else "decrease")
        )
        if (result.code != 200) throw ApiError.Unknown("Changing progress was answered with ${result.code}")
        val errors = YamtrackParsers.parseMessages(result.body).filter { it.first == "error" }
        if (errors.isNotEmpty()) throw ApiError.Validation(mapOf("progress" to errors.map { it.second }))
    }

    override suspend fun setScore(type: MediaType, instanceId: Long, score: Double): Double? {
        val result = session.post("/update-score/${type.slug}/$instanceId", mapOf("score" to formatScore(score)))
        if (result.code != 200) throw ApiError.Unknown("Scoring was answered with ${result.code}")
        val json = runCatching { Json.parseToJsonElement(result.body).jsonObject }.getOrNull()
            ?: throw ApiError.ParseError("score", "not JSON")
        if (json["success"]?.jsonPrimitive?.booleanOrNull != true) {
            val reason = json["error"]?.jsonPrimitive?.contentOrNull ?: "Score not accepted"
            throw ApiError.Validation(mapOf("score" to listOf(reason)))
        }
        return json["score"]?.jsonPrimitive?.doubleOrNull
    }

    override suspend fun markEpisodeWatched(show: MediaKey, seasonNumber: Int, episodeNumber: Int, watchedAt: Long?) {
        val result = session.post(
            "/episode_save",
            mapOf(
                "media_id" to show.mediaId,
                "source" to show.source,
                "season_number" to seasonNumber.toString(),
                "episode_number" to episodeNumber.toString(),
                "end_date" to (watchedAt?.let { YamtrackParsers.formatFormDate(it, withTime = true) } ?: "")
            )
        )
        if (!result.isRedirect) throw ApiError.Unknown("Marking the episode was answered with ${result.code}")
    }

    override suspend fun search(type: MediaType, query: String, source: String?, page: Int): YamtrackSearchPage =
        YamtrackParsers.parseSearch(
            session.get(
                "/search",
                mapOf(
                    "media_type" to type.slug,
                    "q" to query,
                    "page" to page.toString(),
                    "source" to source?.takeIf { it != type.defaultSource }
                )
            )
        )

    override suspend fun details(key: MediaKey): YamtrackMediaDetails {
        // The slug in the URL is decorative; Yamtrack routes on the ids alone.
        val path = when (key.type) {
            MediaType.SEASON -> "/details/${key.source}/tv/${key.mediaId}/x/season/${key.seasonNumber}"
            else -> "/details/${key.source}/${key.type.slug}/${key.mediaId}/x"
        }
        return YamtrackParsers.parseDetails(session.get(path), key)
    }

    override suspend fun calendar(): List<YamtrackCalendarEvent> {
        val token = currentUser().token ?: return emptyList()
        val events = YamtrackParsers.parseIcs(session.getPublic("/calendar/download/$token"))
        if (events.isEmpty()) return emptyList()
        // The feed has exact times but no item links; the calendar page has links. It shows a month
        // at a time, so read each month the feed's events fall in.
        val zone = ZoneId.systemDefault()
        val months = events.map {
            val date = java.time.Instant.ofEpochMilli(it.startsAt).atZone(zone).toLocalDate()
            date.year to date.monthValue
        }.distinct()
        val links = months.flatMap { (year, month) ->
            runCatching {
                YamtrackParsers.parseCalendarLinks(
                    session.get("/calendar", mapOf("month" to month.toString(), "year" to year.toString()))
                )
            }.getOrDefault(emptyList())
        }
        return YamtrackParsers.joinCalendar(events, links)
    }

    override suspend fun customLists(): List<YamtrackCustomList> =
        YamtrackParsers.parseLists(session.get("/lists"))

    /** Collects the reason a save did not stick from the toasts Yamtrack queued for the next page. */
    private suspend fun rejection(fallback: String): ApiError {
        val messages = runCatching { YamtrackParsers.parseMessages(session.get("/settings/account")) }
            .getOrDefault(emptyList())
            .filter { it.first == "error" || it.first == "warning" }
            .map { it.second }
        return if (messages.isEmpty()) {
            ApiError.Validation(mapOf("entry" to listOf(fallback)))
        } else {
            ApiError.Validation(messages.groupBy({ it.substringBefore(':').trim().lowercase() }, { it }))
        }
    }

    private fun YamtrackEntryFields.matches(sent: YamtrackEntryFields, form: YamtrackTrackForm): Boolean {
        if (status != sent.status && !autoCompleted(sent)) return false
        if ("score" in form.availableFields && score != sent.score?.let(::normalizeScore)) return false
        if ("notes" in form.availableFields && notes.orEmpty().trim() != sent.notes.orEmpty().trim()) return false
        // Progress is left out on purpose: Yamtrack raises it to the total when an entry is
        // completed and completes an entry whose progress reaches the total.
        return true
    }

    /** Yamtrack completes an in-progress entry whose progress reached the total by itself. */
    private fun YamtrackEntryFields.autoCompleted(sent: YamtrackEntryFields): Boolean =
        status == com.anisync.android.domain.LibraryStatus.COMPLETED &&
            sent.status == com.anisync.android.domain.LibraryStatus.CURRENT

    /** Yamtrack's score field: 0–10 with one decimal place. */
    private fun normalizeScore(score: Double): Double = Math.round(score.coerceIn(0.0, 10.0) * 10) / 10.0

    private fun formatScore(score: Double): String = normalizeScore(score).toString()

    private fun formatProgress(type: MediaType, progress: Int): String =
        if (type.progressUnit == ProgressUnit.MINUTES) YamtrackParsers.formatMinutes(progress) else progress.toString()

    /** `source/type/id[/season]`, as the per-item form views take it. */
    private fun MediaKey.formPath(): String = buildString {
        append("$source/${type.slug}/$mediaId")
        seasonNumber?.let { append("/$it") }
    }

    /** A server-absolute path (`/yamtrack/…` under a sub-path) back to one relative to the root. */
    private fun String.relativeTo(session: YamtrackSession): String {
        val base = session.baseUrl.encodedPath.trimEnd('/')
        return if (base.isNotEmpty() && startsWith(base)) removePrefix(base) else this
    }
}
