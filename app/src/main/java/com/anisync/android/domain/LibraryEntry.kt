package com.anisync.android.domain

import androidx.compose.runtime.Immutable
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import kotlinx.serialization.Serializable

/** Yamtrack's five statuses. A rewatch is its own entry in [CURRENT], not a status of its own. */
enum class LibraryStatus {
    CURRENT,
    PLANNING,
    COMPLETED,
    DROPPED,
    PAUSED
}

/**
 * One entry in the user's Yamtrack library: the newest tracking row for an item.
 *
 * Yamtrack keeps a rewatch as another row for the same item; the library shows the newest and counts
 * the earlier ones in [rewatches].
 */
@Immutable
@Serializable
data class LibraryEntry(
    /** Yamtrack's id for this row, needed to edit it. 0 until the server has confirmed the entry. */
    val id: Int,
    /** The item's local id (see [com.anisync.android.domain.MediaKeyRegistry]); stable per item. */
    val mediaId: Int,
    val key: MediaKey,
    val title: String,
    val coverUrl: String?,
    /** In the type's unit: episodes, chapters, pages, plays, or minutes for games. */
    val progress: Int,
    /** The total in the same unit, when Yamtrack knows it. */
    val maxProgress: Int? = null,
    val status: LibraryStatus,
    /** 0–10 with one decimal place; null when unscored. */
    val score: Double? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val notes: String? = null,
    /** Earlier rows for the same item: how many times it was finished before this one. */
    val rewatches: Int = 0,
    val createdAt: Long? = null,
    /** When progress last changed. */
    val updatedAt: Long? = null,
    /** Names of the Yamtrack lists this item is on. */
    val customLists: List<String> = emptyList(),
    /** The next release from the calendar, when there is one. */
    val nextAiringEpisode: Int? = null,
    /** Seconds since the epoch of [nextAiringEpisode]. */
    val nextAiringEpisodeTime: Long? = null
) {
    val type: MediaType get() = key.type

    /** Seconds until the next release, or null when none is scheduled or it has passed. */
    val timeUntilNextRelease: Int?
        get() {
            val airingTime = nextAiringEpisodeTime ?: return null
            val remaining = (airingTime - System.currentTimeMillis() / 1000).toInt()
            return if (remaining > 0) remaining else null
        }
}
