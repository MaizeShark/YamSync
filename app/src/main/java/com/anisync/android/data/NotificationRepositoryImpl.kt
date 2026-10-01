package com.anisync.android.data

import com.anisync.android.GetPlanningFirstEpisodesQuery
import com.anisync.android.GetPlanningUpcomingEpisodesQuery
import com.anisync.android.data.util.safeApiCall
import com.anisync.android.domain.AiringSchedule
import com.anisync.android.domain.CoverImage
import com.anisync.android.domain.NotificationRepository
import com.anisync.android.domain.Result
import com.anisync.android.type.MediaType
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.doNotStore
import com.apollographql.cache.normalized.fetchPolicy
import javax.inject.Inject

class NotificationRepositoryImpl @Inject constructor(
    private val apolloClient: ApolloClient
) : NotificationRepository {

    companion object {
        // Only notify about Episode 1 if it aired within the last 7 days
        private const val RECENCY_THRESHOLD_DAYS = 7
        private const val SECONDS_PER_DAY = 24 * 60 * 60
    }

    override suspend fun getFirstEpisodeAirings(mediaIds: List<Int>): Result<List<AiringSchedule>> {
        if (mediaIds.isEmpty()) return Result.Success(emptyList())

        return safeApiCall {
            val currentTime = (System.currentTimeMillis() / 1000).toInt()
            val recencyThreshold = currentTime - (RECENCY_THRESHOLD_DAYS * SECONDS_PER_DAY)

            // Use server-side filtering with airingAfter for recency
            val response = apolloClient.query(
                GetPlanningFirstEpisodesQuery(
                    mediaIds = Optional.present(mediaIds),
                    airingBefore = currentTime,
                    airingAfter = recencyThreshold
                )
            )
            .fetchPolicy(FetchPolicy.NetworkOnly)
            .doNotStore(true)
            .execute()

            // Server now filters by recency, but we still need to verify it's in the past
            // to avoid race conditions with episodes airing exactly now
            response.data?.Page?.airingSchedules?.mapNotNull { airing ->
                airing?.let {
                    val airingAt = it.airingAt ?: 0
                    // Verify episode has actually aired (in the past)
                    if (airingAt < currentTime) {
                        AiringSchedule(
                            id = it.id ?: 0,
                            episode = it.episode ?: 0,
                            airingAt = airingAt.toLong(),
                            mediaId = it.mediaId ?: 0,
                            mediaTitle = it.media?.title?.userPreferred ?: "Unknown",
                            mediaCoverUrl = it.media?.coverImage?.large,
                            mediaCover = CoverImage.of(it.media?.coverImage?.medium, it.media?.coverImage?.large, it.media?.coverImage?.extraLarge),
                            mediaType = it.media?.type ?: MediaType.ANIME
                        )
                    } else {
                        null
                    }
                }
            } ?: emptyList()
        }
    }

    override suspend fun getUpcomingFirstEpisodes(
        mediaIds: List<Int>,
        withinHours: Int
    ): Result<List<AiringSchedule>> {
        if (mediaIds.isEmpty()) return Result.Success(emptyList())

        return safeApiCall {
            val currentTime = (System.currentTimeMillis() / 1000).toInt()
            val maxAiringTime = currentTime + (withinHours * 60 * 60)

            // Use server-side filtering with airingBefore for time window
            val response = apolloClient.query(
                GetPlanningUpcomingEpisodesQuery(
                    mediaIds = Optional.present(mediaIds),
                    airingBefore = maxAiringTime
                )
            )
            .fetchPolicy(FetchPolicy.NetworkOnly)
            .doNotStore(true)
            .execute()

            // Server now handles time filtering, results are already sorted by TIME
            response.data?.Page?.airingSchedules?.mapNotNull { airing ->
                airing?.let {
                    val timeUntil = it.timeUntilAiring ?: Int.MAX_VALUE
                    // Only include if actually in the future (timeUntil > 0)
                    if (timeUntil > 0) {
                        AiringSchedule(
                            id = it.id ?: 0,
                            episode = it.episode ?: 0,
                            airingAt = (it.airingAt ?: 0).toLong(),
                            mediaId = it.mediaId ?: 0,
                            mediaTitle = it.media?.title?.userPreferred ?: "Unknown",
                            mediaCoverUrl = it.media?.coverImage?.large,
                            mediaCover = CoverImage.of(it.media?.coverImage?.medium, it.media?.coverImage?.large, it.media?.coverImage?.extraLarge),
                            mediaType = it.media?.type ?: MediaType.ANIME
                        )
                    } else {
                        null
                    }
                }
            } ?: emptyList()
        }
    }
}
