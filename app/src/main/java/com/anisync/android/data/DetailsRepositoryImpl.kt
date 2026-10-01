package com.anisync.android.data

import com.anisync.android.DeleteMediaListEntryMutation
import com.anisync.android.GetMediaCharactersQuery
import com.anisync.android.GetMediaDetailsQuery
import com.anisync.android.GetMediaStaffQuery
import com.anisync.android.SaveMediaListEntryMutation
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.dao.MediaDetailsDao
import com.anisync.android.data.local.toDomain
import com.anisync.android.data.local.toEntity
import com.anisync.android.data.mapper.toApiStatus
import com.anisync.android.data.mapper.toDomainStatus
import com.anisync.android.data.mapper.todayUtcMillis
import com.anisync.android.data.util.safeApiCall
import com.anisync.android.domain.CharacterInfo
import com.anisync.android.domain.CoverImage
import com.anisync.android.domain.DetailsRepository
import com.anisync.android.domain.ExternalLink
import com.anisync.android.domain.ExternalLinkType
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaDetails
import com.anisync.android.domain.MediaRanking
import com.anisync.android.domain.MediaRankingType
import com.anisync.android.domain.MediaReview
import com.anisync.android.domain.RecommendedMedia
import com.anisync.android.domain.RelatedMedia
import com.anisync.android.domain.Result
import com.anisync.android.domain.Tag
import com.anisync.android.domain.Trailer
import com.anisync.android.domain.VoiceActor
import com.anisync.android.type.MediaType
import com.anisync.android.util.stripHtml
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private const val HOUR_MS = 60L * 60L * 1000L
private const val DAY_MS = 24L * HOUR_MS

private val MONTH_ABBR = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

private fun formatFuzzyDateLong(month: Int?, day: Int?, year: Int?): String? {
    val m = month?.takeIf { it in 1..12 }?.let { MONTH_ABBR[it - 1] }
    val d = day?.let { if (it < 10) "0$it" else it.toString() }
    return when {
        m != null && d != null && year != null -> "$m $d, $year"
        year != null -> year.toString()
        else -> null
    }
}

private fun formatFuzzyDateShort(month: Int?, day: Int?, year: Int?): String? {
    val m = month?.takeIf { it in 1..12 }?.let { MONTH_ABBR[it - 1] }
    return when {
        m != null && day != null && year != null -> "$m $day, $year"
        m != null && day != null -> "$m $day"
        year != null -> year.toString()
        else -> null
    }
}

class DetailsRepositoryImpl @Inject constructor(
    private val apolloClient: ApolloClient,
    private val mediaDetailsDao: MediaDetailsDao,
    private val libraryDao: LibraryDao,
    private val accountStore: com.anisync.android.data.account.AccountStore
) : DetailsRepository {

    private fun currentOwnerId(): Int = accountStore.activeAccount.value?.id ?: -1

    override fun observeMediaDetails(id: Int): Flow<MediaDetails?> {
        return mediaDetailsDao.observeById(id)
            .map { entity -> entity?.toDomain() }
    }

    override suspend fun refreshMediaDetails(id: Int): Result<Unit> {
        return safeApiCall {
            val response = apolloClient.query(
                GetMediaDetailsQuery(id = Optional.present(id))
            )
                .fetchPolicy(FetchPolicy.NetworkOnly)
                .execute()

            val media = response.data?.Media ?: throw Exception("Media not found")

            val listEntry = media.mediaListEntry
            val listStatus = listEntry?.status?.toDomainStatus()

            val characters = media.characters?.edges?.filterNotNull()?.map { edge ->
                CharacterInfo(
                    id = edge.node?.id ?: 0,
                    nameFull = edge.node?.name?.full ?: "Unknown",
                    nameNative = edge.node?.name?.native,
                    nameUserPreferred = edge.node?.name?.userPreferred ?: "Unknown",
                    imageUrl = edge.node?.image?.large,
                    role = edge.role?.name ?: "UNKNOWN"
                )
            }?.distinctBy { "${it.id}_${it.role}" } ?: emptyList()

            val staff = media.staff?.edges?.filterNotNull()?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                com.anisync.android.domain.StaffInfo(
                    id = node.id,
                    nameFull = node.name?.full ?: "Unknown",
                    nameNative = node.name?.native,
                    nameUserPreferred = node.name?.userPreferred ?: node.name?.full ?: "Unknown",
                    imageUrl = node.image?.large,
                    role = edge.role.orEmpty(),
                    primaryOccupations = node.primaryOccupations?.filterNotNull().orEmpty()
                )
            }?.distinctBy { "${it.id}_${it.role}" } ?: emptyList()

            val relations = media.relations?.edges?.filterNotNull()?.map { edge ->
                val node = edge.node
                RelatedMedia(
                    id = node?.id ?: 0,
                    titleRomaji = node?.title?.romaji,
                    titleEnglish = node?.title?.english,
                    titleNative = node?.title?.native,
                    titleUserPreferred = node?.title?.userPreferred ?: "Unknown",
                    coverUrl = node?.coverImage?.large,
                    cover = com.anisync.android.domain.CoverImage.of(node?.coverImage?.medium, node?.coverImage?.large, node?.coverImage?.extraLarge),
                    format = node?.format?.name,
                    status = node?.status?.name,
                    relationType = edge.relationType?.name ?: "UNKNOWN"
                )
            } ?: emptyList()

            val externalLinks = media.externalLinks?.filterNotNull()
                ?.filter { it.isDisabled != true }
                ?.map { link ->
                    ExternalLink(
                        id = link.id,
                        url = link.url,
                        site = link.site,
                        type = when (link.type?.name) {
                            "STREAMING" -> ExternalLinkType.STREAMING
                            "SOCIAL" -> ExternalLinkType.SOCIAL
                            "INFO" -> ExternalLinkType.INFO
                            else -> null
                        },
                        color = link.color,
                        icon = link.icon,
                        language = link.language,
                        notes = link.notes
                    )
                } ?: emptyList()

            val titleRomaji = media.title?.romaji
            val titleEnglish = media.title?.english
            val titleNative = media.title?.native
            val titleUserPreferred = media.title?.userPreferred ?: "Unknown"

            val formattedDate = formatFuzzyDateLong(
                media.startDate?.month, media.startDate?.day, media.startDate?.year
            )
            val formattedEndDate = formatFuzzyDateLong(
                media.endDate?.month, media.endDate?.day, media.endDate?.year
            )

            val tags = media.tags?.filterNotNull()?.map { tag ->
                Tag(
                    name = tag.name ?: "",
                    category = tag.category ?: "",
                    description = tag.description,
                    isMediaSpoiler = tag.isMediaSpoiler ?: false,
                    isGeneralSpoiler = tag.isGeneralSpoiler ?: false,
                    rank = tag.rank
                )
            } ?: emptyList()

            val trailer = media.trailer?.let { trailer ->
                if (trailer.id != null && trailer.site != null) {
                    Trailer(
                        id = trailer.id,
                        site = trailer.site,
                        thumbnail = trailer.thumbnail
                    )
                } else null
            }

            val recommendations =
                media.recommendations?.nodes?.filterNotNull()?.mapNotNull { node ->
                    val rec = node.mediaRecommendation ?: return@mapNotNull null
                    RecommendedMedia(
                        id = rec.id,
                        titleRomaji = rec.title?.romaji,
                        titleEnglish = rec.title?.english,
                        titleNative = rec.title?.native,
                        titleUserPreferred = rec.title?.userPreferred ?: "Unknown",
                        coverUrl = rec.coverImage?.large,
                        cover = com.anisync.android.domain.CoverImage.of(rec.coverImage?.medium, rec.coverImage?.large, rec.coverImage?.extraLarge),
                        format = rec.format?.name,
                        score = rec.averageScore,
                        rating = node.rating ?: 0,
                        userRating = node.userRating?.name
                    )
                } ?: emptyList()

            val reviews = media.reviews?.nodes?.filterNotNull()?.map { node ->
                MediaReview(
                    id = node.id,
                    summary = node.summary ?: "",
                    body = node.body,
                    score = node.score ?: 0,
                    rating = node.rating ?: 0,
                    ratingAmount = node.ratingAmount ?: 0,
                    userRating = node.userRating?.name,
                    userName = node.user?.name ?: "Unknown",
                    userAvatarUrl = node.user?.avatar?.medium,
                    createdAt = (node.createdAt ?: 0).toLong()
                )
            } ?: emptyList()

            // AniList returns animation studios (isMain) and producers/distributors
            // (non-main) in one studio connection; split them here.
            val studioEdges = media.studios?.edges?.filterNotNull().orEmpty()
            val mainStudios = studioEdges
                .filter { it.isMain }
                .mapNotNull { edge -> edge.node?.let { com.anisync.android.domain.StudioRef(it.id, it.name) } }
            val producers = studioEdges
                .filter { !it.isMain }
                .mapNotNull { edge -> edge.node?.let { com.anisync.android.domain.StudioRef(it.id, it.name) } }
            val synonyms = media.synonyms?.filterNotNull().orEmpty()
            // AniList stores hashtags as a single space-separated string (e.g. "#rezero #リゼロ").
            val hashtags = media.hashtag
                ?.split(" ")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val rankings = media.rankings?.filterNotNull()?.mapNotNull { ranking ->
                val type = when (ranking.type) {
                    com.anisync.android.type.MediaRankType.RATED -> MediaRankingType.RATED
                    com.anisync.android.type.MediaRankType.POPULAR -> MediaRankingType.POPULAR
                    else -> return@mapNotNull null
                }
                MediaRanking(
                    rank = ranking.rank,
                    type = type,
                    year = ranking.year,
                    season = ranking.season?.rawValue,
                    allTime = ranking.allTime ?: false,
                    context = ranking.context
                )
            }.orEmpty()

            val details = MediaDetails(
                id = media.id ?: 0,
                titleRomaji = titleRomaji,
                titleEnglish = titleEnglish,
                titleNative = titleNative,
                titleUserPreferred = titleUserPreferred,
                coverUrl = media.coverImage?.extraLarge,
                cover = com.anisync.android.domain.CoverImage.of(media.coverImage?.medium, media.coverImage?.large, media.coverImage?.extraLarge),
                coverColor = media.coverImage?.color,
                bannerUrl = media.bannerImage,
                description = media.description?.stripHtml() ?: "",
                score = media.averageScore,
                meanScore = media.meanScore,
                popularity = media.popularity,
                favourites = media.favourites,
                episodes = media.episodes,
                nextAiringEpisode = media.nextAiringEpisode?.let { airing ->
                    com.anisync.android.domain.NextAiringEpisode(
                        episode = airing.episode,
                        airingAt = airing.airingAt.toLong(),
                        timeUntilAiring = airing.timeUntilAiring
                    )
                },
                chapters = media.chapters,
                volumes = media.volumes,
                type = media.type,
                status = media.status?.name ?: "UNKNOWN",
                format = media.format?.name,
                genres = media.genres?.filterNotNull() ?: emptyList(),
                synonyms = synonyms,
                hashtags = hashtags,
                rankings = rankings,
                source = media.source?.name,
                studio = mainStudios.firstOrNull(),
                studios = mainStudios,
                producers = producers,
                year = media.startDate?.year,
                startDate = formattedDate,
                endDate = formattedEndDate,
                season = media.season?.name,
                seasonYear = media.startDate?.year,
                duration = media.duration,
                tags = tags,
                trailer = trailer,
                listEntryId = listEntry?.id,
                listStatus = listStatus,
                listProgress = listEntry?.progress,
                listNotes = listEntry?.notes,
                listEntryPrivate = listEntry?.`private`,
                listEntryHiddenFromStatusLists = listEntry?.hiddenFromStatusLists,
                characters = characters,
                staff = staff,
                relations = relations,
                externalLinks = externalLinks,
                recommendations = recommendations,
                reviews = reviews,
                isFavourite = media.isFavourite ?: false,
                isRecommendationBlocked = media.isRecommendationBlocked,
                isReviewBlocked = media.isReviewBlocked
            )

            mediaDetailsDao.insert(details.toEntity())
        }
    }

    override suspend fun refreshMediaDetailsIfStale(id: Int): Result<Unit> {
        val cached = mediaDetailsDao.getById(id)
            ?: return refreshMediaDetails(id) // nothing cached yet → must fetch

        val now = System.currentTimeMillis()

        // The cached airing countdown has already elapsed (an episode aired since we
        // cached this): the data is provably stale, so refetch regardless of TTL.
        // nextAiringEpisodeTime is a Unix timestamp in seconds.
        val countdownElapsed =
            cached.nextAiringEpisodeTime?.let { it * 1000L < now } == true

        val age = now - cached.lastUpdated
        val stale = countdownElapsed || age >= staleAfterMillis(cached.status)

        return if (stale) refreshMediaDetails(id) else Result.Success(Unit)
    }

    /**
     * How long a cached media-details row stays fresh, keyed by AniList media status.
     * Airing / upcoming media changes often (new episodes, countdown, schedule
     * publication) so it expires quickly; finished media rarely changes so it lingers.
     */
    private fun staleAfterMillis(status: String): Long = when (status) {
        "RELEASING" -> 3 * HOUR_MS          // weekly episodes + live countdown
        "NOT_YET_RELEASED" -> 6 * HOUR_MS   // air schedule can be published any time
        "FINISHED", "CANCELLED" -> 7 * DAY_MS
        else -> DAY_MS                       // HIATUS / unknown
    }

    override suspend fun updateMediaListEntry(
        mediaId: Int,
        status: LibraryStatus,
        progress: Int
    ): Result<Unit> {
        return safeApiCall {
            val apiStatus = status.toApiStatus()

            val response = apolloClient.mutation(
                SaveMediaListEntryMutation(
                    mediaId = Optional.present(mediaId),
                    status = Optional.present(apiStatus),
                    progress = Optional.present(progress)
                )
            ).execute()

            if (response.data?.SaveMediaListEntry != null && !response.hasErrors()) {
                refreshMediaDetails(mediaId)
                val owner = currentOwnerId()
                val existingEntry = libraryDao.getEntry(owner, mediaId)

                if (existingEntry != null) {
                    libraryDao.updateStatusAndProgress(owner, mediaId, status, progress)
                } else {
                    val savedEntry = response.data?.SaveMediaListEntry
                    val cachedMedia = mediaDetailsDao.getById(mediaId)

                    if (cachedMedia != null) {
                        val newEntry = com.anisync.android.data.local.entity.LibraryEntryEntity(
                            id = savedEntry?.id ?: 0,
                            ownerId = owner,
                            mediaId = mediaId,
                            titleRomaji = cachedMedia.titleRomaji,
                            titleEnglish = cachedMedia.titleEnglish,
                            titleNative = cachedMedia.titleNative,
                            titleUserPreferred = cachedMedia.titleUserPreferred,
                            coverUrl = cachedMedia.coverUrl,
                            progress = progress,
                            totalEpisodes = cachedMedia.episodes,
                            totalChapters = cachedMedia.chapters,
                            totalVolumes = cachedMedia.volumes,
                            mediaType = cachedMedia.mediaType,
                            status = status,
                            nextAiringEpisode = cachedMedia.nextAiringEpisode,
                            timeUntilAiring = null,
                            mediaStatus = cachedMedia.status,
                            nextAiringEpisodeTime = cachedMedia.nextAiringEpisodeTime,
                            score = 0.0,
                            rewatches = 0,
                            notes = null,
                            startedAt = if (status == LibraryStatus.CURRENT) todayUtcMillis() else null,
                            completedAt = null,
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            mediaStartDate = null
                        )
                        libraryDao.insertOrReplace(newEntry)
                    }
                }
            } else {
                val errorMessage = response.errors?.firstOrNull()?.message ?: "Update failed"
                throw Exception(errorMessage)
            }
        }
    }

    override suspend fun deleteMediaListEntry(entryId: Int, mediaId: Int): Result<Unit> {
        return safeApiCall {
            val response = apolloClient.mutation(
                DeleteMediaListEntryMutation(id = Optional.present(entryId))
            ).execute()

            if (response.data?.DeleteMediaListEntry?.deleted == true && !response.hasErrors()) {
                libraryDao.deleteByMediaId(currentOwnerId(), mediaId)
            } else {
                val errorMessage = response.errors?.firstOrNull()?.message ?: "Delete failed"
                throw Exception(errorMessage)
            }
        }
    }

    @Volatile
    private var cachedViewerId: Int? = null

    override suspend fun getMediaCharacters(
        mediaId: Int,
        page: Int,
        perPage: Int,
        sort: List<com.anisync.android.type.CharacterSort>?
    ): Result<Pair<List<CharacterInfo>, Boolean>> {
        return safeApiCall {
            val response = apolloClient.query(
                GetMediaCharactersQuery(
                    id = Optional.present(mediaId),
                    page = Optional.present(page),
                    perPage = Optional.present(perPage),
                    sort = Optional.presentIfNotNull(sort)
                )
            )
                // A show's cast does not change. Character carries a seven day window.
                .fetchPolicy(FetchPolicy.CacheFirst)
                .execute()

            val connection = response.data?.Media?.characters
                ?: throw Exception("Media not found")

            val characters = connection.edges?.filterNotNull()?.map { edge ->
                val voiceActors = edge.voiceActors?.filterNotNull()?.mapNotNull { va ->
                    VoiceActor(
                        id = va.id ?: 0,
                        nameFull = va.name?.full ?: "Unknown",
                        nameNative = va.name?.native,
                        nameUserPreferred = va.name?.userPreferred ?: va.name?.full ?: "Unknown",
                        imageUrl = va.image?.large ?: va.image?.medium,
                        language = va.languageV2
                    )
                }.orEmpty()
                CharacterInfo(
                    id = edge.node?.id ?: 0,
                    nameFull = edge.node?.name?.full ?: "Unknown",
                    nameNative = edge.node?.name?.native,
                    nameUserPreferred = edge.node?.name?.userPreferred ?: "Unknown",
                    imageUrl = edge.node?.image?.large,
                    role = edge.role?.name ?: "UNKNOWN",
                    voiceActors = voiceActors
                )
            }?.distinctBy { "${it.id}_${it.role}" } ?: emptyList()

            characters to (connection.pageInfo?.hasNextPage ?: false)
        }
    }

    override suspend fun getMediaStaff(
        mediaId: Int,
        page: Int,
        perPage: Int,
        sort: List<com.anisync.android.type.StaffSort>?
    ): Result<Pair<List<com.anisync.android.domain.StaffInfo>, Boolean>> {
        return safeApiCall {
            val response = apolloClient.query(
                GetMediaStaffQuery(
                    id = Optional.present(mediaId),
                    page = Optional.present(page),
                    perPage = Optional.present(perPage),
                    sort = Optional.presentIfNotNull(sort)
                )
            )
                // Same as the cast: crew credits are settled once a show has aired.
                .fetchPolicy(FetchPolicy.CacheFirst)
                .execute()

            val connection = response.data?.Media?.staff
                ?: throw Exception("Media not found")

            val staff = connection.edges?.filterNotNull()?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                com.anisync.android.domain.StaffInfo(
                    id = node.id,
                    nameFull = node.name?.full ?: "Unknown",
                    nameNative = node.name?.native,
                    nameUserPreferred = node.name?.userPreferred ?: node.name?.full ?: "Unknown",
                    imageUrl = node.image?.large,
                    role = edge.role.orEmpty(),
                    primaryOccupations = node.primaryOccupations?.filterNotNull().orEmpty()
                )
            }?.distinctBy { "${it.id}_${it.role}" } ?: emptyList()

            staff to (connection.pageInfo?.hasNextPage ?: false)
        }
    }

}

// One edge per character, already server-ordered by the characters' own favourites
// (FAVOURITES_DESC); edge.media carries the roles, so no client-side regrouping.
/**
 * AniList reports at most 500 for a nested connection's `pageInfo.total`, and it is the cap rather
 * than the data: Mayumi Tanaka, Erica Schroeder and Renato Novara all come back as exactly 500
 * however large their real cast lists are, at any page size (`lastPage` tracks the cap too). A total
 * sitting on the cap therefore means "at least this many", so it is dropped instead of being shown
 * as a count — every voice actor claiming 500 roles is worse than claiming none.
 */
private const val ANILIST_CONNECTION_TOTAL_CAP = 500

private fun Int?.exactConnectionTotal(): Int? = this?.takeIf { it < ANILIST_CONNECTION_TOTAL_CAP }

