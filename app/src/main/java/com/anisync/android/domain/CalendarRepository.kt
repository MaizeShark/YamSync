package com.anisync.android.domain

/** The release calendar from the user's Yamtrack server. */
interface CalendarRepository {
    /** Reads the server's calendar into the local cache the widgets and calendar screen use. */
    suspend fun sync(): Result<Unit>

    /**
     * Every release in the half-open window `[weekStartEpochSec, weekEndEpochSec)`, in Unix seconds,
     * sorted by time. Syncs first when the cache is old.
     */
    suspend fun getWeekSchedule(weekStartEpochSec: Long, weekEndEpochSec: Long): Result<List<AiringEpisode>>
}
