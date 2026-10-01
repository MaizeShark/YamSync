package com.anisync.android.domain.model

import kotlinx.serialization.Serializable

/** What a progress number counts for a [MediaType]. */
enum class ProgressUnit {
    EPISODE,
    CHAPTER,
    /** Time played, stored as minutes. */
    MINUTES,
    PAGE,
    ISSUE,
    PLAY,
    /** Nothing to count: a movie is watched or not, an episode is its own unit. */
    NONE
}

/**
 * The kinds of media Yamtrack tracks. [slug] is Yamtrack's own name for the type, as it appears in
 * URLs, forms and the CSV export.
 */
@Serializable
enum class MediaType(
    val slug: String,
    val progressUnit: ProgressUnit,
    /** Where search and new items come from when nothing else is said, matching Yamtrack's config. */
    val defaultSource: String,
    /** Every metadata source Yamtrack can search for this type, default first. */
    val sources: List<String>
) {
    ANIME("anime", ProgressUnit.EPISODE, "mal", listOf("mal")),
    MANGA("manga", ProgressUnit.CHAPTER, "mal", listOf("mal", "mangaupdates")),
    TV("tv", ProgressUnit.EPISODE, "tmdb", listOf("tmdb")),
    SEASON("season", ProgressUnit.EPISODE, "tmdb", listOf("tmdb")),
    EPISODE("episode", ProgressUnit.NONE, "tmdb", listOf("tmdb")),
    MOVIE("movie", ProgressUnit.NONE, "tmdb", listOf("tmdb")),
    GAME("game", ProgressUnit.MINUTES, "igdb", listOf("igdb")),
    BOOK("book", ProgressUnit.PAGE, "hardcover", listOf("hardcover", "openlibrary")),
    COMIC("comic", ProgressUnit.ISSUE, "comicvine", listOf("comicvine")),
    BOARDGAME("boardgame", ProgressUnit.PLAY, "bgg", listOf("bgg"));

    /**
     * Whether progress for this type is something the user edits directly. TV and season progress
     * is derived from watched episodes, and movies and episodes have none.
     */
    val hasEditableProgress: Boolean
        get() = progressUnit != ProgressUnit.NONE && this != TV && this != SEASON

    /** Types a user tracks and searches on their own; seasons and episodes hang off a show. */
    val isTopLevel: Boolean
        get() = this != SEASON && this != EPISODE

    companion object {
        fun fromSlug(slug: String): MediaType? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Identifies a media item the way Yamtrack does: the metadata [source] it came from, its [type] and
 * the source's own [mediaId]. Seasons and episodes add their numbers, since they share the show's id.
 */
@Serializable
data class MediaKey(
    val source: String,
    val type: MediaType,
    val mediaId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) {
    /** The path segments Yamtrack's per-item views take: `source/type/id[/season[/episode]]`. */
    fun pathSegments(): List<String> = buildList {
        add(source)
        add(type.slug)
        add(mediaId)
        seasonNumber?.let { add(it.toString()) }
        episodeNumber?.let { add(it.toString()) }
    }

    /** A stable string form, for Room keys and navigation arguments. */
    fun asString(): String = pathSegments().joinToString("/")

    companion object {
        /** Parses [asString] output back; null when it is not one. */
        fun parse(value: String): MediaKey? {
            val parts = value.split('/')
            if (parts.size !in 3..5) return null
            val type = MediaType.fromSlug(parts[1]) ?: return null
            return MediaKey(
                source = parts[0],
                type = type,
                mediaId = parts[2],
                seasonNumber = parts.getOrNull(3)?.toIntOrNull(),
                episodeNumber = parts.getOrNull(4)?.toIntOrNull()
            )
        }
    }
}
