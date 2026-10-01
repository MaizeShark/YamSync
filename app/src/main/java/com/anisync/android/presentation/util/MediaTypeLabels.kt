package com.anisync.android.presentation.util

import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.anisync.android.R
import com.anisync.android.domain.model.MediaType
import com.anisync.android.domain.model.ProgressUnit

/** The type's name for one item ("Movie"). */
@StringRes
fun MediaType.labelRes(): Int = when (this) {
    MediaType.ANIME -> R.string.media_type_anime
    MediaType.MANGA -> R.string.media_type_manga
    MediaType.TV -> R.string.media_type_tv_show
    MediaType.SEASON -> R.string.media_type_season
    MediaType.EPISODE -> R.string.media_type_episode
    MediaType.MOVIE -> R.string.media_type_movie
    MediaType.GAME -> R.string.media_type_game
    MediaType.BOOK -> R.string.media_type_book
    MediaType.COMIC -> R.string.media_type_comic
    MediaType.BOARDGAME -> R.string.media_type_boardgame
}

/** The type's name for a collection ("Movies"), for tabs and filters. */
@StringRes
fun MediaType.pluralLabelRes(): Int = when (this) {
    MediaType.ANIME -> R.string.media_types_anime
    MediaType.MANGA -> R.string.media_types_manga
    MediaType.TV -> R.string.media_types_tv
    MediaType.SEASON -> R.string.media_types_season
    MediaType.EPISODE -> R.string.media_types_episode
    MediaType.MOVIE -> R.string.media_types_movie
    MediaType.GAME -> R.string.media_types_game
    MediaType.BOOK -> R.string.media_types_book
    MediaType.COMIC -> R.string.media_types_comic
    MediaType.BOARDGAME -> R.string.media_types_boardgame
}

@Composable
fun MediaType.label(): String = stringResource(labelRes())

@Composable
fun MediaType.pluralLabel(): String = stringResource(pluralLabelRes())

val MediaType.icon: ImageVector
    get() = when (this) {
        MediaType.ANIME -> Icons.Outlined.Animation
        MediaType.MANGA -> Icons.AutoMirrored.Filled.MenuBook
        MediaType.TV, MediaType.SEASON, MediaType.EPISODE -> Icons.Default.Tv
        MediaType.MOVIE -> Icons.Default.Movie
        MediaType.GAME -> Icons.Default.SportsEsports
        MediaType.BOOK -> Icons.Default.AutoStories
        MediaType.COMIC -> Icons.Default.VideoLibrary
        MediaType.BOARDGAME -> Icons.Default.Casino
    }

/** The unit progress counts in, as plurals ("episode"/"episodes"); null for none or play time. */
@PluralsRes
fun MediaType.unitRes(): Int? = when (progressUnit) {
    ProgressUnit.EPISODE -> R.plurals.unit_episodes
    ProgressUnit.CHAPTER -> R.plurals.unit_chapters
    ProgressUnit.PAGE -> R.plurals.unit_pages
    ProgressUnit.ISSUE -> R.plurals.unit_issues
    ProgressUnit.PLAY -> R.plurals.unit_plays
    ProgressUnit.MINUTES, ProgressUnit.NONE -> null
}

/** The unit word that goes after [count] ("1 episode", "3 episodes"); null when there is none. */
@Composable
fun MediaType.unitLabel(count: Int): String? = unitRes()?.let { pluralStringResource(it, count) }

/** Play time as hours and minutes ("12h 30m"). */
fun formatPlayTime(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

/**
 * Progress as the user reads it: "5 / 28 episodes", "120 pages", "12h 30m". Null when the type
 * has no progress (movies).
 */
@Composable
fun progressText(type: MediaType, progress: Int, total: Int?): String? {
    if (type.progressUnit == ProgressUnit.NONE) return null
    if (type.progressUnit == ProgressUnit.MINUTES) return formatPlayTime(progress)
    val unit = type.unitLabel(total?.takeIf { it > 0 } ?: progress).orEmpty()
    return if (total != null && total > 0) "$progress / $total $unit".trim() else "$progress $unit".trim()
}

/** A compact progress for cards: "5/28", "120", "12h 30m"; null for none. */
fun compactProgress(type: MediaType, progress: Int, total: Int?): String? = when (type.progressUnit) {
    ProgressUnit.NONE -> null
    ProgressUnit.MINUTES -> formatPlayTime(progress)
    else -> if (total != null && total > 0) "$progress/$total" else progress.toString()
}
