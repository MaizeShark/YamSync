package com.anisync.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.anisync.android.data.local.entity.LibraryEntryEntity
import com.anisync.android.data.local.entity.LibraryStatusProjection
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(ownerId: Int): Flow<List<LibraryEntryEntity>>

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId AND mediaType = :type ORDER BY title COLLATE NOCASE ASC")
    fun observeByType(ownerId: Int, type: String): Flow<List<LibraryEntryEntity>>

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId")
    suspend fun getAll(ownerId: Int): List<LibraryEntryEntity>

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId AND mediaType = :type")
    suspend fun getByType(ownerId: Int, type: String): List<LibraryEntryEntity>

    /** In-progress entries with something left to watch, most recently touched first. */
    @Query(
        "SELECT * FROM library_entries WHERE ownerId = :ownerId AND status = 'CURRENT' " +
            "AND (maxProgress IS NULL OR progress < maxProgress) ORDER BY lastUpdated DESC"
    )
    suspend fun getUpNext(ownerId: Int): List<LibraryEntryEntity>

    @Query(
        "SELECT * FROM library_entries WHERE ownerId = :ownerId AND status = 'CURRENT' " +
            "AND (maxProgress IS NULL OR progress < maxProgress) ORDER BY lastUpdated DESC LIMIT 1"
    )
    suspend fun getMostRecentWatching(ownerId: Int): LibraryEntryEntity?

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId AND status = 'CURRENT' ORDER BY lastUpdated DESC")
    fun observeInProgress(ownerId: Int): Flow<List<LibraryEntryEntity>>

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId AND mediaId = :mediaId LIMIT 1")
    suspend fun getEntry(ownerId: Int, mediaId: Int): LibraryEntryEntity?

    @Query("SELECT * FROM library_entries WHERE ownerId = :ownerId AND mediaId = :mediaId LIMIT 1")
    fun observeEntry(ownerId: Int, mediaId: Int): Flow<LibraryEntryEntity?>

    @Query("SELECT mediaId, status FROM library_entries WHERE ownerId = :ownerId")
    fun observeListStatuses(ownerId: Int): Flow<List<LibraryStatusProjection>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LibraryEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entry: LibraryEntryEntity)

    @Query("UPDATE library_entries SET progress = :progress, lastUpdated = :timestamp WHERE ownerId = :ownerId AND mediaId = :mediaId")
    suspend fun updateProgress(ownerId: Int, mediaId: Int, progress: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM library_entries WHERE ownerId = :ownerId AND mediaId = :mediaId")
    suspend fun deleteByMediaId(ownerId: Int, mediaId: Int)

    @Query("DELETE FROM library_entries WHERE ownerId = :ownerId")
    suspend fun deleteForOwner(ownerId: Int)

    /** Replaces the account's whole library with a fresh sync in one step, so readers never see it half-written. */
    @Transaction
    suspend fun replaceAll(ownerId: Int, entries: List<LibraryEntryEntity>) {
        deleteForOwner(ownerId)
        insertAll(entries)
    }

    @Query("DELETE FROM library_entries")
    suspend fun deleteAll()
}
