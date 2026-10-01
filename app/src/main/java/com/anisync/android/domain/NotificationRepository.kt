package com.anisync.android.domain

/**
 * Repository interface for notification-related data operations: premiere airings for the
 * user's planned media.
 */
interface NotificationRepository {
    /**
     * Get first episode airings for media in the user's planning list.
     * @param mediaIds List of media IDs to check
     * @return List of airing schedules for Episode 1 or error
     */
    suspend fun getFirstEpisodeAirings(mediaIds: List<Int>): Result<List<AiringSchedule>>
    
    /**
     * Get Episode 1 airings scheduled within the next [withinHours] hours.
     * Used for upcoming airing notifications.
     * @param mediaIds List of media IDs to check
     * @param withinHours Hours window to look ahead (default: 24)
     * @return List of upcoming airing schedules or error
     */
    suspend fun getUpcomingFirstEpisodes(
        mediaIds: List<Int>,
        withinHours: Int = 24
    ): Result<List<AiringSchedule>>
}
