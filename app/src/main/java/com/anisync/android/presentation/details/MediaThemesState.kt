package com.anisync.android.presentation.details

import com.anisync.android.domain.MediaTheme

/**
 * Openings and endings, loaded separately from the details page they sit on.
 *
 * [hasLoaded] tells an empty list from one that has not arrived yet, which is what decides
 * between drawing the skeleton rail and drawing no section at all.
 */
data class MediaThemesState(
    val animeSlug: String? = null,
    val themes: List<MediaTheme> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Seconds AnimeThemes asked us to wait, from a 429's Retry-After. Null for other failures. */
    val retryAfterSeconds: Long? = null,
    val hasLoaded: Boolean = false
)
