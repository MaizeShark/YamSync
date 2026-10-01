package com.anisync.android.data.yamtrack

import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType

/**
 * Everything AniSync asks of a Yamtrack server.
 *
 * The only implementation today, [com.anisync.android.data.yamtrack.html.HtmlYamtrackApi], drives
 * the web UI the way a browser does, because Yamtrack has no API. Nothing above this interface knows
 * that, so a JSON implementation can replace it without touching the rest of the app.
 *
 * Every call throws [com.anisync.android.data.util.ApiError] on failure; callers wrap them in
 * `safeApiCall`. A session that has run out and cannot be renewed surfaces as
 * [com.anisync.android.data.util.ApiError.SessionExpired].
 */
interface YamtrackApi {

    /** Signs in and returns who that is. Throws `LoginFailed` for wrong credentials. */
    suspend fun login(username: String, password: String): YamtrackUser

    /** The signed-in user, confirming the session is still good. */
    suspend fun currentUser(): YamtrackUser

    /** Every tracked row of every type, rewatches as separate rows. */
    suspend fun libraryEntries(): List<YamtrackEntry>

    /** In-progress items from the home page, which carry the ids and totals the export lacks. */
    suspend fun inProgressItems(): List<YamtrackHomeItem>

    /**
     * The tracking form for [key]: the entry with [instanceId], or the newest one when null. Its
     * `instanceId` is null when the user has not tracked the item.
     */
    suspend fun trackForm(key: MediaKey, instanceId: Long? = null): YamtrackTrackForm

    /**
     * Saves [fields] onto the entry [instanceId], or creates a new entry when null (which is how a
     * rewatch is added). Returns the form as Yamtrack has it afterwards, which is how a rejected
     * value is caught: Yamtrack itself always answers a save with a redirect.
     */
    suspend fun saveEntry(key: MediaKey, instanceId: Long?, fields: YamtrackEntryFields): YamtrackTrackForm

    suspend fun deleteEntry(type: MediaType, instanceId: Long)

    /** Steps progress by one unit (an episode, or 30 minutes for a game). */
    suspend fun stepProgress(type: MediaType, instanceId: Long, increase: Boolean)

    /** Sets only the score, which Yamtrack answers with JSON. Returns the score it stored. */
    suspend fun setScore(type: MediaType, instanceId: Long, score: Double): Double?

    /** Records a watch of one TV episode, creating the season entry if needed. */
    suspend fun markEpisodeWatched(show: MediaKey, seasonNumber: Int, episodeNumber: Int, watchedAt: Long?)

    suspend fun search(type: MediaType, query: String, source: String? = null, page: Int = 1): YamtrackSearchPage

    suspend fun details(key: MediaKey): YamtrackMediaDetails

    /** Releases from 30 days back to 90 days ahead, matched to their items where possible. */
    suspend fun calendar(): List<YamtrackCalendarEvent>

    suspend fun customLists(): List<YamtrackCustomList>
}
