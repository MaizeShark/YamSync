package com.anisync.android.domain

import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * The signed-in user's library, cached in Room and kept in step with Yamtrack.
 *
 * Reads come from the cache. Writes update the cache first so the UI reacts at once, then go to the
 * server; a write the server refuses is rolled back and returned as an error.
 */
interface LibraryRepository {
    /** Every entry of [type], or of every type when null. */
    fun observeLibrary(type: MediaType? = null): Flow<List<LibraryEntry>>

    /** The status of each item already in the library, keyed by local media id. */
    fun observeListStatuses(): Flow<Map<Int, LibraryStatus>>

    fun observeEntry(mediaId: Int): Flow<LibraryEntry?>

    /** Reads the whole library from the server and replaces the cache with it. */
    suspend fun refreshLibrary(): Result<Unit>

    /** Sets progress; a no-op for types whose progress Yamtrack derives (TV, seasons). */
    suspend fun updateProgress(mediaId: Int, progress: Int): Result<Unit>

    /** Saves every editable field of [entry]. */
    suspend fun updateEntry(entry: LibraryEntry): Result<LibraryEntry>

    /** Adds the item to the library with [status]. Returns the new entry. */
    suspend fun addEntry(key: MediaKey, status: LibraryStatus, title: String?, imageUrl: String?): Result<LibraryEntry>

    /** Starts another watch/read of [entry]: a new entry in progress, the old one kept as history. */
    suspend fun addRewatch(entry: LibraryEntry): Result<LibraryEntry>

    /** Removes the newest entry for the item; an earlier rewatch, if any, takes its place. */
    suspend fun deleteEntry(entry: LibraryEntry): Result<Unit>
}
