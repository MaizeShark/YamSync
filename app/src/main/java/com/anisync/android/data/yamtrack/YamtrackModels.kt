package com.anisync.android.data.yamtrack

import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.MediaKey

/**
 * What the Yamtrack client hands to the rest of the app. These mirror what Yamtrack shows rather
 * than how it stores it, and stay free of HTML so a future JSON backend can produce the same types.
 */

/** The signed-in user, as far as Yamtrack reveals it. */
data class YamtrackUser(
    val username: String,
    /** Token for the calendar feed and media-server webhooks; null when the server has none set. */
    val token: String?
)

/**
 * One tracked row. A rewatch is another row for the same [key], so a library can hold several of
 * these per item; [createdAt] orders them.
 */
data class YamtrackEntry(
    val key: MediaKey,
    val title: String,
    val imageUrl: String?,
    /** Null for episodes, which have no status of their own. */
    val status: LibraryStatus?,
    /** 0–10 with one decimal place. */
    val score: Double?,
    /** Episodes, chapters, pages… in the type's unit; minutes for games. */
    val progress: Int?,
    val startDate: Long?,
    val endDate: Long?,
    val notes: String?,
    val createdAt: Long?,
    val progressedAt: Long?
)

/** An in-progress or planned item as the home page shows it. */
data class YamtrackHomeItem(
    val key: MediaKey,
    val instanceId: Long,
    val title: String,
    val imageUrl: String?,
    val progress: Int?,
    val maxProgress: Int?
)

/**
 * The tracking form for one item, as Yamtrack would show it for editing. [instanceId] is null when
 * the user has no entry for the item yet.
 */
data class YamtrackTrackForm(
    val key: MediaKey,
    val instanceId: Long?,
    val title: String?,
    val fields: YamtrackEntryFields,
    /** The fields the form actually has; movies have no progress, TV and seasons only a few. */
    val availableFields: Set<String>,
    /** Whether dates carry a time of day (Yamtrack's TRACK_TIME setting) or are dates only. */
    val datesHaveTime: Boolean
)

/**
 * Everything editable about one entry. A save sends all of it: Yamtrack's form replaces the whole
 * row, so a field left null is cleared, not kept.
 */
data class YamtrackEntryFields(
    val status: LibraryStatus,
    val score: Double? = null,
    val progress: Int? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val notes: String? = null
)

/** What the user already has for a search result or related item, when Yamtrack shows it. */
data class YamtrackTrackedSummary(
    val instanceId: Long?,
    val score: Double?,
    val progress: Int?,
    val maxProgress: Int?
)

data class YamtrackSearchResult(
    val key: MediaKey,
    val title: String,
    val imageUrl: String?,
    val tracked: YamtrackTrackedSummary? = null
)

data class YamtrackSearchPage(
    val page: Int,
    val totalPages: Int,
    val totalResults: Int?,
    val results: List<YamtrackSearchResult>
)

data class YamtrackCastMember(
    val name: String,
    val role: String?,
    val imageUrl: String?
)

data class YamtrackRelatedSection(
    val title: String,
    val items: List<YamtrackSearchResult>
)

data class YamtrackEpisode(
    val number: Int,
    val title: String?,
    val airDate: String?,
    val runtime: String?,
    val imageUrl: String?,
    val overview: String?,
    /** How many times the user watched it; 0 when never. */
    val watchCount: Int
)

data class YamtrackSeasonRef(
    val number: Int,
    val title: String
)

data class YamtrackStreamingProvider(
    val name: String,
    val logoUrl: String?
)

/** A media details page: metadata from the provider plus whatever the user has tracked. */
data class YamtrackMediaDetails(
    val key: MediaKey,
    val title: String,
    /** For a season page, the season's own title; [title] is then the show's. */
    val seasonTitle: String?,
    val imageUrl: String?,
    val synopsis: String?,
    val genres: List<String>,
    val score: Double?,
    val scoreCount: Int?,
    /** The provider's details table, in page order: label to one or more values. */
    val info: List<Pair<String, List<String>>>,
    val sourceUrl: String?,
    val externalLinks: List<Pair<String, String>>,
    val cast: List<YamtrackCastMember>,
    val related: List<YamtrackRelatedSection>,
    val seasons: List<YamtrackSeasonRef>,
    val episodes: List<YamtrackEpisode>,
    val streamingProviders: List<YamtrackStreamingProvider>
)

/** A release from the calendar, joined to the item it belongs to when that could be worked out. */
data class YamtrackCalendarEvent(
    val uid: String,
    /** Yamtrack's own wording, e.g. "Severance E5". */
    val summary: String,
    val startsAt: Long,
    val key: MediaKey?,
    val title: String?,
    /** The episode, chapter or issue number, when the release has one. */
    val contentNumber: Int?,
    val imageUrl: String?
)

data class YamtrackCustomList(
    val id: Long,
    val name: String,
    val description: String?,
    val itemCount: Int?
)
