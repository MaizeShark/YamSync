package com.anisync.android.domain

import kotlinx.coroutines.flow.Flow

interface DetailsRepository {
    /**
     * Observe media details from local cache (reactive).
     */
    fun observeMediaDetails(id: Int): Flow<MediaDetails?>

    /**
     * Fetch fresh media details from network and update cache.
     */
    suspend fun refreshMediaDetails(id: Int): Result<Unit>

    /**
     * Refresh from network only when the cached copy is missing or older than its
     * status-based TTL (stale-while-revalidate). Cheap no-op when the cache is still
     * fresh. Called on screen entry so a revisited page self-updates (e.g. a newly
     * published airing schedule or changed cover) without a manual pull-to-refresh,
     * while finished media rarely re-hits the API.
     */
    suspend fun refreshMediaDetailsIfStale(id: Int): Result<Unit>

    /**
     * Update media list entry (status, progress).
     */
    suspend fun updateMediaListEntry(
        mediaId: Int,
        status: LibraryStatus,
        progress: Int
    ): Result<Unit>

    /**
     * Delete media list entry.
     * @param entryId The list entry ID to delete from the API
     * @param mediaId The media ID to remove from local library cache
     */
    suspend fun deleteMediaListEntry(entryId: Int, mediaId: Int): Result<Unit>

    /**
     * Fetch a page of this media's full character (Cast) list. The base
     * [MediaDetails] only carries the first page (perPage 25) for the preview rail,
     * so the See-all grid pages through this to show the complete cast (#83).
     * Returns the page's characters and whether a further page exists.
     *
     * [sort] maps to AniList `CharacterSort` and is applied server-side; null requests the
     * API default (moderator/relevance) order, which is what the AniList website shows.
     */
    suspend fun getMediaCharacters(
        mediaId: Int,
        page: Int,
        perPage: Int = 25,
        sort: List<com.anisync.android.type.CharacterSort>? = null
    ): Result<Pair<List<CharacterInfo>, Boolean>>

    /**
     * Fetch a page of this media's full staff list. Mirrors [getMediaCharacters];
     * the base [MediaDetails] only carries the first page for the preview rail.
     *
     * [sort] maps to AniList `StaffSort`. Unlike characters, staff RELEVANCE is well-curated
     * (creator/director/design/music first), so callers pass `[RELEVANCE, ID]` as the default
     * rather than relying on the API's unsorted order.
     */
    suspend fun getMediaStaff(
        mediaId: Int,
        page: Int,
        perPage: Int = 25,
        sort: List<com.anisync.android.type.StaffSort>? = null
    ): Result<Pair<List<StaffInfo>, Boolean>>
}
