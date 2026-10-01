package com.anisync.android.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe route definitions for navigation.
 * - Objects: Routes without arguments
 * - Data classes: Routes with arguments
 */

@Serializable
object Login

@Serializable
object Library

/** Home tab: what is in progress and what comes out next. */
@Serializable
object Home

/** Search across every media type Yamtrack tracks. */
@Serializable
object Search

/**
 * Release calendar from the Yamtrack server. Reached from the Library and Home top bars.
 */
@Serializable
object Calendar

/**
 * Notes journal — every library entry the viewer has written a note on, searchable. Reached from the
 * Library top bar. Surfaces notes that were otherwise only visible inside the edit sheet.
 */
@Serializable
object Notes

@Serializable
object Profile

/**
 * Details screen route with media ID and source screen for shared element matching.
 * @param mediaId The local id of the media to display (see MediaKeyRegistry)
 * @param sourceScreen The screen that initiated navigation (e.g., "library", "home", "profile")
 *                     Used to match shared element keys and prevent cross-tab transitions.
 */
@Serializable
data class MediaDetails(
    val mediaId: Int,
    val sourceScreen: String = "unknown"
)

/**
 * Full list of a title's openings and endings, opened from the section arrow on the
 * media details page.
 * @param mediaId The ID of the media
 * @param mediaTitle The title of the media (for display under the app bar)
 */
@Serializable
data class MediaThemes(
    val mediaId: Int,
    val mediaTitle: String,
    val totalEpisodes: Int? = null,
    val coverUrl: String? = null
)

/**
 * Main settings hub screen.
 */
@Serializable
object Settings

/**
 * Look and Feel settings (theme, colors, title language, streaming service, haptic).
 */
@Serializable
object SettingsLookAndFeel

/**
 * Theme picker subscreen — light/dark/system mode + AMOLED pure-black toggle.
 */
@Serializable
object SettingsTheme

/**
 * App language picker subscreen.
 */
@Serializable
object SettingsLanguage

/**
 * AniList settings: account management (add / switch / remove / logout) merged with the AniList
 * account options (adult content, languages, score format, activity, profile color).
 */
@Serializable
object SettingsAniList

/**
 * Notification settings with master toggle and granular controls.
 */
@Serializable
object SettingsNotifications

/**
 * Storage management (cache size, clear cache).
 */
@Serializable
object SettingsStorage

/**
 * About app (version, licenses, acknowledgments).
 */
@Serializable
object SettingsAbout

/**
 * Sponsors screen — lists GitHub Sponsors backers.
 */
@Serializable
object SettingsSponsors

/**
 * Open source licenses screen.
 */
@Serializable
object SettingsOpenSourceLicenses

/**
 * Acknowledgments screen (credits to contributors and libraries).
 */
@Serializable
object SettingsAcknowledgments

/**
 * App updates screen.
 */
@Serializable
object SettingsUpdates

/**
 * Developer and source links screen.
 */
@Serializable
object SettingsLinks

/**
 * Developer tools screen (debug builds only).
 */
@Serializable
object SettingsDeveloperTools

/**
 * Font playground — live variable-font axis sliders (debug builds only).
 */
@Serializable
object SettingsFontPlayground
