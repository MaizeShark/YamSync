package com.anisync.android.data

import com.anisync.android.data.yamtrack.YamtrackGateway
import com.anisync.android.data.yamtrack.YamtrackMediaDetails
import com.anisync.android.data.yamtrack.YamtrackSearchResult
import com.anisync.android.domain.CastMember
import com.anisync.android.domain.EpisodeInfo
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.MediaCard
import com.anisync.android.domain.MediaDetails
import com.anisync.android.domain.MediaKeyRegistry
import com.anisync.android.domain.MediaRepository
import com.anisync.android.domain.MediaSearchPage
import com.anisync.android.domain.RelatedGroup
import com.anisync.android.domain.Result
import com.anisync.android.domain.StreamingProvider
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton

/** Search and details pages from Yamtrack, which proxies them from each type's metadata provider. */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val gateway: YamtrackGateway,
    private val registry: MediaKeyRegistry,
    private val libraryRepository: LibraryRepository,
) : MediaRepository {

    override suspend fun details(mediaId: Int): Result<MediaDetails> {
        val key = registry.keyFor(mediaId) ?: return Result.Error("Unknown item")
        return when (val result = gateway.call { details(key) }) {
            is Result.Error -> result
            is Result.Success -> {
                val details = result.data.toDomain(mediaId)
                // Remember the total, so the library can show "5 / 28" for this item from now on.
                registry.idFor(key, details.title.takeIf { key.type != MediaType.SEASON }, details.imageUrl, details.maxProgress)
                Result.Success(details)
            }
        }
    }

    override suspend fun search(type: MediaType, query: String, source: String?, page: Int): Result<MediaSearchPage> =
        when (val result = gateway.call { search(type, query, source, page) }) {
            is Result.Error -> result
            is Result.Success -> Result.Success(
                MediaSearchPage(
                    page = result.data.page,
                    totalPages = result.data.totalPages,
                    totalResults = result.data.totalResults,
                    results = result.data.results.map { it.toCard() }
                )
            )
        }

    override suspend fun markEpisodeWatched(seasonMediaId: Int, episodeNumber: Int): Result<Unit> {
        val season = registry.keyFor(seasonMediaId) ?: return Result.Error("Unknown item")
        val seasonNumber = season.seasonNumber ?: return Result.Error("Not a season")
        val show = MediaKey(season.source, MediaType.TV, season.mediaId)
        val result = gateway.call { markEpisodeWatched(show, seasonNumber, episodeNumber, System.currentTimeMillis()) }
        // Season and show progress are derived from watched episodes; pick the new numbers up.
        if (result is Result.Success) libraryRepository.refreshLibrary()
        return result
    }

    private suspend fun YamtrackSearchResult.toCard() = MediaCard(
        mediaId = registry.idFor(key, title, imageUrl),
        key = key,
        title = title,
        imageUrl = imageUrl
    )

    private suspend fun YamtrackMediaDetails.toDomain(mediaId: Int) = MediaDetails(
        mediaId = mediaId,
        key = key,
        title = title,
        subtitle = seasonTitle,
        imageUrl = imageUrl,
        synopsis = synopsis,
        genres = genres,
        score = score,
        scoreCount = scoreCount,
        info = info,
        sourceUrl = sourceUrl,
        externalLinks = externalLinks,
        cast = cast.map { CastMember(it.name, it.role, it.imageUrl) },
        related = related.map { group -> RelatedGroup(group.title, group.items.map { it.toCard() }) },
        seasons = seasons.map { season ->
            val seasonKey = MediaKey(key.source, MediaType.SEASON, key.mediaId, seasonNumber = season.number)
            MediaCard(registry.idFor(seasonKey, season.title), seasonKey, season.title, null)
        },
        episodes = episodes.map {
            EpisodeInfo(it.number, it.title, it.airDate, it.runtime, it.imageUrl, it.overview, it.watchCount)
        },
        streamingProviders = streamingProviders.map { StreamingProvider(it.name, it.logoUrl) },
        maxProgress = maxProgressFrom(this)
    )

    /** The item's total, from whichever row of the provider's details states it. */
    private fun maxProgressFrom(details: YamtrackMediaDetails): Int? {
        if (details.key.type == MediaType.SEASON && details.episodes.isNotEmpty()) return details.episodes.size
        val labels = when (details.key.type.progressUnit) {
            com.anisync.android.domain.model.ProgressUnit.EPISODE -> setOf("episodes", "number of episodes")
            com.anisync.android.domain.model.ProgressUnit.CHAPTER -> setOf("chapters", "number of chapters")
            com.anisync.android.domain.model.ProgressUnit.PAGE -> setOf("pages", "number of pages")
            com.anisync.android.domain.model.ProgressUnit.ISSUE -> setOf("issues", "number of issues")
            else -> return null
        }
        return details.info.firstOrNull { (label, _) -> label.lowercase() in labels }
            ?.second?.firstOrNull()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
    }
}
