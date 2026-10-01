package com.anisync.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.anisync.android.data.local.entity.MediaItemEntity

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun getByKey(mediaKey: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaItemEntity): Long

    @Query(
        "UPDATE media_items SET title = COALESCE(:title, title), imageUrl = COALESCE(:imageUrl, imageUrl), " +
            "maxProgress = COALESCE(:maxProgress, maxProgress) WHERE id = :id"
    )
    suspend fun update(id: Int, title: String?, imageUrl: String?, maxProgress: Int?)

    /** The local id for [mediaKey], creating it on first sight and refreshing what is known. */
    @Transaction
    suspend fun idFor(mediaKey: String, title: String?, imageUrl: String?, maxProgress: Int? = null): Int {
        val existing = getByKey(mediaKey)
        if (existing != null) {
            if (title != null || imageUrl != null || maxProgress != null) update(existing.id, title, imageUrl, maxProgress)
            return existing.id
        }
        val inserted = insert(MediaItemEntity(mediaKey = mediaKey, title = title, imageUrl = imageUrl, maxProgress = maxProgress))
        return if (inserted > 0) inserted.toInt() else getByKey(mediaKey)!!.id
    }
}
