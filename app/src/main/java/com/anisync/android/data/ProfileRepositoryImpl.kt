package com.anisync.android.data

import com.anisync.android.data.util.InflightTracker
import com.anisync.android.GetFullUserProfileQuery
import com.anisync.android.GetViewerQuery
import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.local.dao.UserProfileDao
import com.anisync.android.data.local.toDomain
import com.anisync.android.data.local.toEntity
import com.anisync.android.data.mapper.mapFuzzyDateToLong
import com.anisync.android.data.mapper.toDomainStatus
import com.anisync.android.data.util.safeApiCall
import android.os.SystemClock
import android.os.Trace
import com.anisync.android.domain.CachePolicy
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.ProfileRefreshTimings
import com.anisync.android.domain.ProfileRepository
import com.anisync.android.domain.Result
import com.anisync.android.domain.UserProfile
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.anisync.android.data.mapper.toDomain as activityFieldsToDomain

class ProfileRepositoryImpl @Inject constructor(
    private val apolloClient: ApolloClient,
    private val userProfileDao: UserProfileDao,
    private val accountStore: AccountStore
) : ProfileRepository {

    /**
     * Shares one in-flight request between concurrent callers asking for the same thing.
     *
     * This used to be a per-key mutex, which serialised callers and then ran the block again for
     * each of them: the second caller waited for the first and still issued its own request.
     * [InflightTracker] hands them the first caller's result instead.
     */
    private val inflight = InflightTracker()

    private suspend fun <T> dedupe(key: String, block: suspend () -> T): T =
        inflight.deduplicate(key, block = block)

    private fun CachePolicy.toFetchPolicy(): FetchPolicy = when (this) {
        CachePolicy.CacheFirst -> FetchPolicy.CacheFirst
        CachePolicy.NetworkOnly -> FetchPolicy.NetworkOnly
        CachePolicy.NetworkFirst -> FetchPolicy.NetworkFirst
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProfile(): Flow<UserProfile?> {
        // Re-subscribe per active account so the own-profile cache is account-scoped (the row's
        // primary key is the user id), and switching accounts shows the right cached profile.
        return accountStore.activeAccount
            .flatMapLatest { account ->
                userProfileDao.observe(account?.id ?: -1)
            }
            .map { entity -> entity?.toDomain() }
    }

    private suspend fun fetchUserProfileTimed(
        username: String,
        forceNetwork: Boolean
    ): Result<Pair<UserProfile, ProfileRefreshTimings>> = dedupe("profile:${username}:$forceNetwork") {
        safeApiCall {
            val isOwnProfile = username.isBlank()

            // Resolve the userId up front when we can. The own profile reuses its
            // cached id; a cold first launch with no cache pays a one-time
            // GetViewer to learn id + name. Target profiles are addressed by name,
            // their id being unknown until User resolves.
            var knownUserId: Int? = null
            var queryName: String? = null
            if (isOwnProfile) {
                knownUserId = accountStore.activeAccount.value?.id?.takeIf { it > 0 }
                if (knownUserId == null) {
                    val viewerResponse = apolloClient.query(GetViewerQuery())
                        .fetchPolicy(FetchPolicy.NetworkOnly)
                        .execute()
                    val viewer = viewerResponse.data?.Viewer
                        ?: throw Exception("Unable to get current user")
                    knownUserId = viewer.id
                    queryName = viewer.name
                }
            } else {
                queryName = username
            }

            val policy = if (forceNetwork) FetchPolicy.NetworkOnly else FetchPolicy.CacheFirst

            val profileQueryStart = SystemClock.elapsedRealtime()
            Trace.beginSection("AniSync.Profile.Query.FullProfile")
            val response = try {
                apolloClient.query(
                    GetFullUserProfileQuery(
                        userId = knownUserId?.let { Optional.present(it) } ?: Optional.absent(),
                        name = queryName?.let { Optional.present(it) } ?: Optional.absent(),
                        includeViewer = Optional.present(false)
                    )
                )
                    .fetchPolicy(policy)
                    .execute()
            } finally {
                Trace.endSection()
            }
            val profileQueryMs = SystemClock.elapsedRealtime() - profileQueryStart

            // A transport or parse failure leaves data AND errors null, so hasErrors() is false and
            // the null-User branch below would report a missing user for what is really an HTTP
            // status. That is how a hard 400 read as "User not found" for months.
            response.exception?.let { throw it }

            if (response.hasErrors()) {
                val firstError = response.errors?.firstOrNull()?.message
                throw Exception(firstError ?: "Failed to load user profile")
            }

            val user = response.data?.User
                ?: throw Exception("User not found: @${queryName ?: knownUserId}")

            val stats = user.statistics?.anime
            val mangaStats = user.statistics?.manga
            val minutesWatched = stats?.minutesWatched ?: 0
            val daysWatched = minutesWatched / 1440f

            // Parse anime status counts
            val statusCounts = stats?.statuses?.filterNotNull()?.associate { 
                it.status to (it.count ?: 0) 
            } ?: emptyMap()
            
            val animeStatusCounts = com.anisync.android.domain.AnimeStatusCounts(
                watching = statusCounts[com.anisync.android.type.MediaListStatus.CURRENT] ?: 0,
                completed = statusCounts[com.anisync.android.type.MediaListStatus.COMPLETED] ?: 0,
                onHold = statusCounts[com.anisync.android.type.MediaListStatus.PAUSED] ?: 0,
                dropped = statusCounts[com.anisync.android.type.MediaListStatus.DROPPED] ?: 0,
                planning = statusCounts[com.anisync.android.type.MediaListStatus.PLANNING] ?: 0
            )
            
            val topGenres = stats?.genres?.filterNotNull()?.map { genre ->
                com.anisync.android.domain.GenreStat(
                    genre = genre.genre ?: "Unknown",
                    count = genre.count ?: 0,
                    meanScore = genre.meanScore?.toFloat() ?: 0f
                )
            } ?: emptyList()

            val profile = UserProfile(
                id = user.id ?: 0,
                name = user.name ?: "Unknown",
                profileColor = user.options?.profileColor,
                avatarUrl = user.avatar?.large,
                bannerUrl = user.bannerImage,
                about = user.about,
                activeAt = user.updatedAt?.toLong()?.times(1000),
                animeCount = stats?.count ?: 0,
                daysWatched = daysWatched,
                mangaCount = mangaStats?.count ?: 0,
                chaptersRead = mangaStats?.chaptersRead ?: 0,
                meanScore = stats?.meanScore?.toFloat() ?: 0f,
                animeStatusCounts = animeStatusCounts,
                topGenres = topGenres,
                donatorTier = user.donatorTier ?: 0,
                donatorBadge = user.donatorBadge,
                moderatorRoles = user.moderatorRoles?.filterNotNull()?.map { it.name } ?: emptyList(),
                createdAt = user.createdAt?.toLong()?.times(1000)
            )

            val timings = ProfileRefreshTimings(profileQueryMs = profileQueryMs)
            profile to timings
        }
    }

    override suspend fun refreshProfile(username: String, forceNetwork: Boolean): Result<Unit> {
        return when (val r = refreshProfileTimed(username, forceNetwork)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> r
        }
    }

    override suspend fun refreshProfileTimed(
        username: String,
        forceNetwork: Boolean
    ): Result<ProfileRefreshTimings> {
        return when (val result = fetchUserProfileTimed(username, forceNetwork)) {
            is Result.Success -> {
                userProfileDao.insert(result.data.first.toEntity())
                Result.Success(result.data.second)
            }
            is Result.Error -> result
        }
    }

    override suspend fun getUserDayActivities(
        userId: Int,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        policy: CachePolicy
    ): Result<List<com.anisync.android.domain.UserActivity>> {
        return dedupe("dayActivities:$userId:$fromEpochSeconds:$policy") {
            safeApiCall {
                val response = apolloClient.query(
                    com.anisync.android.GetUserDayActivitiesQuery(
                        userId = Optional.present(userId),
                        from = fromEpochSeconds.toInt(),
                        to = toEpochSeconds.toInt()
                    )
                )
                    .fetchPolicy(policy.toFetchPolicy())
                    .execute()

                if (response.hasErrors()) {
                    throw Exception(
                        response.errors?.firstOrNull()?.message ?: "Failed to load activities"
                    )
                }

                response.data?.Page?.activities
                    ?.filterNotNull()
                    ?.mapNotNull { it.activityFields.activityFieldsToDomain() }
                    ?.distinctBy { it.id }
                    ?: emptyList()
            }
        }
    }

    override suspend fun getUserAnimeList(username: String): Result<List<LibraryEntry>> =
        dedupe("animeList:$username") {
            fetchUserList(username, com.anisync.android.type.MediaType.ANIME)
        }

    override suspend fun getUserMangaList(username: String): Result<List<LibraryEntry>> =
        dedupe("mangaList:$username") {
            fetchUserList(username, com.anisync.android.type.MediaType.MANGA)
        }

    private suspend fun fetchUserList(username: String, type: com.anisync.android.type.MediaType): Result<List<LibraryEntry>> {
        return safeApiCall {
            val query = com.anisync.android.GetUserLibraryQuery(username = username, type = type)

            val response = apolloClient.query(query)
                .fetchPolicy(FetchPolicy.CacheFirst)
                .execute()
                .takeIf { it.data?.MediaListCollection != null }
                ?: apolloClient.query(query)
                    .fetchPolicy(FetchPolicy.NetworkOnly)
                    .execute()

            val lists = response.data?.MediaListCollection?.lists?.filterNotNull() ?: emptyList()
            // The owner's global score format, so their scores render in their own units (#78).
            val scoreFormat = response.data?.MediaListCollection?.user?.mediaListOptions?.scoreFormat
                ?.let { mapScoreFormat(it.name) }
            val entryMap = HashMap<Int, LibraryEntry>(lists.sumOf { it.entries?.size ?: 0 })

            lists.forEach { group ->
                val listName = group.name ?: return@forEach
                val isCustom = group.isCustomList ?: false

                group.entries?.filterNotNull()?.forEach { entry ->
                    val entryId = entry.id ?: return@forEach
                    val media = entry.media
                    val existing = entryMap[entryId]

                    if (existing == null) {
                        val status = entry.status?.toDomainStatus() ?: LibraryStatus.UNKNOWN

                        entryMap[entryId] = LibraryEntry(
                            id = entryId,
                            mediaId = media?.id ?: 0,
                            titleRomaji = media?.title?.romaji,
                            titleEnglish = media?.title?.english,
                            titleNative = media?.title?.native,
                            titleUserPreferred = media?.title?.userPreferred ?: "Unknown Title",
                            coverUrl = media?.coverImage?.extraLarge,
                            cover = com.anisync.android.domain.CoverImage.of(media?.coverImage?.medium, media?.coverImage?.large, media?.coverImage?.extraLarge),
                            progress = entry.progress ?: 0,
                            totalEpisodes = media?.episodes,
                            totalChapters = media?.chapters,
                            totalVolumes = media?.volumes,
                            type = media?.type,
                            format = media?.format,
                            status = status,
                            nextAiringEpisode = media?.nextAiringEpisode?.episode,
                            timeUntilAiring = media?.nextAiringEpisode?.timeUntilAiring,
                            mediaStatus = media?.status?.name,
                            nextAiringEpisodeTime = media?.nextAiringEpisode?.airingAt?.toLong(),
                            score = entry.score,
                            rewatches = entry.repeat ?: 0,
                            notes = entry.notes,
                            scoreFormat = scoreFormat,
                            updatedAt = entry.updatedAt?.toLong()?.times(1000L),
                            createdAt = entry.createdAt?.toLong()?.times(1000L),
                            customLists = if (isCustom) listOf(listName) else emptyList(),
                            isPrivate = entry.`private` ?: false,
                            hiddenFromStatusLists = entry.hiddenFromStatusLists ?: false
                        )
                    } else if (isCustom && !existing.customLists.contains(listName)) {
                        entryMap[entryId] = existing.copy(customLists = existing.customLists + listName)
                    }
                }
            }

            entryMap.values.sortedByDescending { it.updatedAt }
        }
    }
}
