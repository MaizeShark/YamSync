package com.anisync.android.domain

import kotlinx.coroutines.flow.Flow

/**
 * Timings captured during a profile refresh. Emitted to logcat (`AniSyncPerf`) so device-side
 * issue reports can attribute slow refreshes.
 */
data class ProfileRefreshTimings(
    val profileQueryMs: Long
)

/**
 * Caller-controlled cache policy. Lets callers choose between hitting Apollo's
 * normalized cache and bypassing it without leaking the Apollo `FetchPolicy`
 * type into the domain layer. Maps 1-to-1 to Apollo `FetchPolicy` in the impl.
 *
 * - [CacheFirst]: Apollo cache first; falls through to network on miss. Used on
 *   tab re-entry within the staleness window.
 * - [NetworkOnly]: Always hit network. Used on user-initiated pull-to-refresh.
 * - [NetworkFirst]: Network first; falls back to cache on network failure.
 *   Used as the default for read-only queries that want freshness when possible
 *   but tolerate a stale value when offline or rate-limited.
 */
enum class CachePolicy { CacheFirst, NetworkOnly, NetworkFirst }

interface ProfileRepository {
    /**
     * Observe user profile from local cache (reactive).
     */
    fun observeProfile(): Flow<UserProfile?>

    /**
     * Fetch fresh profile and update cache. When [forceNetwork] is false the
     * Apollo normalized cache is consulted first, falling through to network
     * on a miss — used for cold-open paths where instant render beats freshness.
     */
    suspend fun refreshProfile(username: String, forceNetwork: Boolean = true): Result<Unit>

    /**
     * Same as [refreshProfile] but also returns per-phase timings.
     */
    suspend fun refreshProfileTimed(username: String, forceNetwork: Boolean = true): Result<ProfileRefreshTimings>

    /**
     * Fetch the activities a user posted inside one day, newest-first.
     *
     * [fromEpochSeconds] and [toEpochSeconds] bound the same UTC day the Activity History
     * heatmap buckets into, so a day's bar and the list behind it describe the same window.
     * AniList's own per-day count may still differ, since `activityHistory` counts every kind
     * of activity and this asks for a filtered set.
     */
    suspend fun getUserDayActivities(
        userId: Int,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        policy: CachePolicy = CachePolicy.NetworkFirst
    ): Result<List<UserActivity>>

    /**
     * Fetch user's anime list.
     */
    suspend fun getUserAnimeList(username: String): Result<List<LibraryEntry>>

    /**
     * Fetch user's manga list.
     */
    suspend fun getUserMangaList(username: String): Result<List<LibraryEntry>>
}
