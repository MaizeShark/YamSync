package com.anisync.android.domain

/** A release on the calendar: an episode, chapter or issue of something the user tracks. */
data class AiringEpisode(
    val id: Int,
    /** The episode, chapter or issue number; 0 when the release has none (a movie, a book). */
    val episode: Int,
    /** Unix time (seconds) of the release, in UTC. */
    val airingAt: Long,
    /** Local media id; 0 when the release could not be matched to an item. */
    val mediaId: Int,
    val title: String,
    val coverImageUrl: String?,
    /** The media type's slug (`anime`, `season`, `comic`…). */
    val format: String?,
    /** True when the item is in the user's library (any status). */
    val isOnList: Boolean,
    /** The user's status for the item, if it is in the library. */
    val listStatus: LibraryStatus?
)
