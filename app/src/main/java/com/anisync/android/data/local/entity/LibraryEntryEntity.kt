package com.anisync.android.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.anisync.android.domain.LibraryStatus

/**
 * The newest tracking row per item, per account. Yamtrack keeps rewatches as further rows; the
 * library only needs the newest, with the earlier ones counted in [rewatches].
 */
@Entity(
    tableName = "library_entries",
    primaryKeys = ["ownerId", "mediaId"],
    indices = [
        Index(value = ["ownerId", "mediaType"]),
        Index(value = ["ownerId", "status"]),
        Index(value = ["updatedAt"]),
        Index(value = ["createdAt"]),
        Index(value = ["score"])
    ]
)
data class LibraryEntryEntity(
    /** Local id of the account this row belongs to. */
    val ownerId: Int,
    /** Local id of the item, from [MediaItemEntity]. */
    val mediaId: Int,
    /** Yamtrack's id for the row; 0 for an entry added here that the server has not confirmed. */
    val instanceId: Int,
    /** [com.anisync.android.domain.model.MediaKey.asString]. */
    val mediaKey: String,
    /** [com.anisync.android.domain.model.MediaType.slug], kept apart for filtering by type. */
    val mediaType: String,
    val title: String,
    val coverUrl: String?,
    val progress: Int,
    val maxProgress: Int?,
    val status: LibraryStatus,
    val score: Double?,
    val startedAt: Long?,
    val completedAt: Long?,
    val notes: String?,
    val rewatches: Int,
    val createdAt: Long?,
    val updatedAt: Long?,
    val customLists: List<String> = emptyList(),
    val nextAiringEpisode: Int? = null,
    val nextAiringEpisodeTime: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
