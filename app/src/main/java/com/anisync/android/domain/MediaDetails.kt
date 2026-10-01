package com.anisync.android.domain

import androidx.compose.runtime.Immutable
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType

/** An item as a card: search results, related titles, a show's seasons. */
@Immutable
data class MediaCard(
    /** Local id (see [MediaKeyRegistry]). */
    val mediaId: Int,
    val key: MediaKey,
    val title: String,
    val imageUrl: String?
) {
    val type: MediaType get() = key.type
}

@Immutable
data class MediaSearchPage(
    val page: Int,
    val totalPages: Int,
    val totalResults: Int?,
    val results: List<MediaCard>
) {
    val hasNextPage: Boolean get() = page < totalPages
}

@Immutable
data class CastMember(val name: String, val role: String?, val imageUrl: String?)

@Immutable
data class RelatedGroup(val title: String, val items: List<MediaCard>)

@Immutable
data class EpisodeInfo(
    val number: Int,
    val title: String?,
    val airDate: String?,
    val runtime: String?,
    val imageUrl: String?,
    val overview: String?,
    /** How often the user watched it; 0 when never. */
    val watchCount: Int
)

@Immutable
data class StreamingProvider(val name: String, val logoUrl: String?)

/** A details page as Yamtrack shows it: the provider's metadata for one item. */
@Immutable
data class MediaDetails(
    val mediaId: Int,
    val key: MediaKey,
    /** The item's title; for a season, the show's. */
    val title: String,
    /** For a season, its own title ("Season 1"). */
    val subtitle: String?,
    val imageUrl: String?,
    val synopsis: String?,
    val genres: List<String>,
    /** The provider's community score, 0–10. */
    val score: Double?,
    val scoreCount: Int?,
    /** The provider's facts in page order, e.g. "Episodes" to ["28"]. */
    val info: List<Pair<String, List<String>>>,
    val sourceUrl: String?,
    val externalLinks: List<Pair<String, String>>,
    val cast: List<CastMember>,
    val related: List<RelatedGroup>,
    /** A show's seasons. */
    val seasons: List<MediaCard>,
    /** A season's episodes. */
    val episodes: List<EpisodeInfo>,
    val streamingProviders: List<StreamingProvider>,
    /** The total in the type's unit (episodes, chapters, pages…), when the page states it. */
    val maxProgress: Int?
) {
    val type: MediaType get() = key.type
}

interface MediaRepository {
    suspend fun details(mediaId: Int): Result<MediaDetails>

    suspend fun search(type: MediaType, query: String, source: String? = null, page: Int = 1): Result<MediaSearchPage>

    /** Records a watch of one episode of a season (or of a show, by season number). */
    suspend fun markEpisodeWatched(seasonMediaId: Int, episodeNumber: Int): Result<Unit>
}
