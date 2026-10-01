package com.anisync.android.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.anisync.android.presentation.calendar.CalendarScreen
import com.anisync.android.presentation.notes.NotesJournalScreen
import com.anisync.android.presentation.details.MediaDetailsScreen
import com.anisync.android.presentation.details.MediaThemesScreen
import com.anisync.android.presentation.home.HomeScreen
import com.anisync.android.presentation.search.SearchScreen
import com.anisync.android.presentation.library.LibraryListDetail
import com.anisync.android.presentation.library.LibraryScreen
import com.anisync.android.presentation.login.LoginScreen
import com.anisync.android.presentation.profile.ProfileScreen
import com.anisync.android.presentation.settings.AboutScreen
import com.anisync.android.presentation.settings.AcknowledgmentsScreen
import com.anisync.android.presentation.settings.AniListSettingsScreen
import com.anisync.android.presentation.settings.DeveloperToolsScreen
import com.anisync.android.presentation.settings.FontSettingsScreen
import com.anisync.android.presentation.settings.LanguageScreen
import com.anisync.android.presentation.settings.LinksScreen
import com.anisync.android.presentation.settings.LookAndFeelScreen
import com.anisync.android.presentation.settings.NotificationsScreen
import com.anisync.android.presentation.settings.OpenSourceLicensesScreen
import com.anisync.android.presentation.settings.SettingsListDetail
import com.anisync.android.presentation.settings.SponsorsScreen
import com.anisync.android.presentation.settings.StorageScreen
import com.anisync.android.presentation.settings.ThemeScreen
import com.anisync.android.presentation.settings.UpdatesScreen
import com.anisync.android.presentation.util.AniLinkCallbacks
import com.anisync.android.presentation.util.LocalAniLinkCallbacks

// =============================================================================
// TAB ORDER HELPER
// =============================================================================
// Defines the order of tabs for determining slide direction during navigation.
// Lower order = further left in the navigation hierarchy.

private val tabOrder = mapOf(
    Library::class.qualifiedName to 0,
    Home::class.qualifiedName to 1,
    Profile::class.qualifiedName to 2
)

/**
 * Determines slide direction for tab transitions based on relative position.
 * @return true if navigating forward (left to right), false if backward (right to left)
 */
private fun isForwardNavigation(fromRoute: String?, toRoute: String?): Boolean {
    val fromOrder = tabOrder[fromRoute] ?: 0
    val toOrder = tabOrder[toRoute] ?: 0
    return toOrder > fromOrder
}

// =============================================================================
// MAIN NAVIGATION HOST
// =============================================================================

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AniSyncNavHost(
    navController: NavHostController,
    onMediaClick: (mediaId: Int, sourceScreen: String) -> Unit,
    modifier: Modifier = Modifier,
    startDestination: Any = Library
) {
    // =============================================================================
    // MATERIAL 3 MOTION SPECS (Memoized)
    // =============================================================================
    val motionScheme = MaterialTheme.motionScheme

    // Default specs — used for the deliberate detail push/pop motion.
    val spatialSpec = remember(motionScheme) { motionScheme.defaultSpatialSpec<IntOffset>() }
    val scaleSpec = remember(motionScheme) { motionScheme.defaultSpatialSpec<Float>() }
    val effectsSpec = remember(motionScheme) { motionScheme.defaultEffectsSpec<Float>() }

    // Fast specs — used for tab-to-tab switching to keep the bottom bar feeling snappy.
    val fastSpatialSpec = remember(motionScheme) { motionScheme.fastSpatialSpec<IntOffset>() }
    val fastEffectsSpec = remember(motionScheme) { motionScheme.fastEffectsSpec<Float>() }

    // Tab slide offset (small, since the X-axis is for sibling tabs not full pushes).
    val sharedAxisOffsetFraction = 0.20f

    // =============================================================================
    // TRANSITION HELPERS
    // =============================================================================

    // ---- Tab transitions (X-axis, fast) -------------------------------------
    fun sharedAxisXEnter(forward: Boolean): EnterTransition {
        val offsetMultiplier = if (forward) 1 else -1
        return slideInHorizontally(
            animationSpec = fastSpatialSpec,
            initialOffsetX = { (it * sharedAxisOffsetFraction * offsetMultiplier).toInt() }
        ) + fadeIn(animationSpec = fastEffectsSpec)
    }

    fun sharedAxisXExit(forward: Boolean): ExitTransition {
        val offsetMultiplier = if (forward) -1 else 1
        return slideOutHorizontally(
            animationSpec = fastSpatialSpec,
            targetOffsetX = { (it * sharedAxisOffsetFraction * offsetMultiplier).toInt() }
        ) + fadeOut(animationSpec = fastEffectsSpec)
    }

    // ---- Detail push/pop (parallax + scale) ---------------------------------
    // PixelPlayer-inspired motion. Names preserved (`sharedAxisZ*`) so existing
    // callsites stay; the bodies now produce parallax-slide + scale rather than
    // the prior pure Z-axis (scale-only) transition.

    fun sharedAxisZEnter(): EnterTransition {
        // Push: incoming screen slides in from the right edge.
        return slideInHorizontally(
            animationSpec = spatialSpec,
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = effectsSpec)
    }

    fun sharedAxisZExit(): ExitTransition {
        // Push: outgoing screen drifts left a third of its width (parallax) and fades.
        return slideOutHorizontally(
            animationSpec = spatialSpec,
            targetOffsetX = { -it / 3 }
        ) + fadeOut(animationSpec = effectsSpec)
    }

    fun sharedAxisZPopEnter(): EnterTransition {
        // Pop: re-entering screen slides from the parallax position back to its
        // place, with a slight zoom-in for depth.
        return slideInHorizontally(
            animationSpec = spatialSpec,
            initialOffsetX = { -it / 3 }
        ) + scaleIn(
            animationSpec = scaleSpec,
            initialScale = 0.9f
        )
    }

    fun sharedAxisZPopExit(): ExitTransition {
        // Pop: outgoing detail slides off to the right and scales down for depth.
        return slideOutHorizontally(
            animationSpec = spatialSpec,
            targetOffsetX = { it }
        ) + scaleOut(
            animationSpec = scaleSpec,
            targetScale = 0.75f
        )
    }

    SharedTransitionLayout(modifier = modifier) {
        // Provide centralized link routing callbacks so any screen can navigate
        // in-app when a recognizable media URL is clicked in rich text.
        val aniLinkCallbacks = remember(navController) {
            AniLinkCallbacks(
                onMediaClick = { mediaId ->
                    navController.navigateSafely(MediaDetails(mediaId, "link"))
                }
            )
        }

        CompositionLocalProvider(LocalAniLinkCallbacks provides aniLinkCallbacks) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
        ) {
            // =================================================================
            // LOGIN SCREEN
            // =================================================================
            // Full slide transitions for authentication flow
            composable<Login>(
                enterTransition = { sharedAxisXEnter(forward = true) },
                exitTransition = { sharedAxisXExit(forward = true) },
                popEnterTransition = { sharedAxisXEnter(forward = false) },
                popExitTransition = { sharedAxisXExit(forward = false) }
            ) {
                LoginScreen()
            }

            // =================================================================
            // MAIN TABS - Shared Axis X (Horizontal)
            // =================================================================
            // Tab navigation uses directional awareness for natural feel

            composable<Library>(
                enterTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = initialState.destination.route,
                        toRoute = Library::class.qualifiedName
                    )
                    sharedAxisXEnter(forward = !forward)
                },
                exitTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = Library::class.qualifiedName,
                        toRoute = targetState.destination.route
                    )
                    sharedAxisXExit(forward = forward)
                },
                popEnterTransition = { sharedAxisXEnter(forward = false) },
                popExitTransition = { sharedAxisXExit(forward = false) }
            ) {
                // Optimization: Memoize the callback to prevent unnecessary recompositions of LibraryScreen
                val onLibraryMediaClick = remember(onMediaClick) { 
                    { mediaId: Int -> onMediaClick(mediaId, "library") } 
                }
                
                LibraryListDetail(
                    navController = navController,
                    onMediaClickFullScreen = onLibraryMediaClick,
                    onNavigateToCalendar = { navController.navigate(Calendar) },
                    onNavigateToNotes = { navController.navigate(Notes) },
                    // An empty list is a dead end without this: same options the bottom bar uses,
                    // so the tab switch saves and restores state rather than stacking a screen.
                    onBrowseDiscover = {
                        navController.navigate(Home) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this
                )
            }

            composable<Home>(
                deepLinks = listOf(
                    navDeepLink { uriPattern = "anisync://home" }
                ),
                enterTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = initialState.destination.route,
                        toRoute = Home::class.qualifiedName
                    )
                    sharedAxisXEnter(forward = forward)
                },
                exitTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = Home::class.qualifiedName,
                        toRoute = targetState.destination.route
                    )
                    sharedAxisXExit(forward = forward)
                },
                popEnterTransition = { sharedAxisXEnter(forward = false) },
                popExitTransition = { sharedAxisXExit(forward = false) }
            ) {
                HomeScreen(
                    onMediaClick = { onMediaClick(it, "home") },
                    onSearchClick = { navController.navigate(Search) },
                    onCalendarClick = { navController.navigate(Calendar) },
                    onSettingsClick = { navController.navigate(Settings) }
                )
            }

            composable<Search>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                SearchScreen(
                    onBackClick = { navController.popBackStack() },
                    onMediaClick = { navController.navigate(MediaDetails(it, "search")) }
                )
            }

            composable<Profile>(
                enterTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = initialState.destination.route,
                        toRoute = Profile::class.qualifiedName
                    )
                    sharedAxisXEnter(forward = forward)
                },
                exitTransition = {
                    val forward = isForwardNavigation(
                        fromRoute = Profile::class.qualifiedName,
                        toRoute = targetState.destination.route
                    )
                    sharedAxisXExit(forward = forward)
                },
                popEnterTransition = { sharedAxisXEnter(forward = false) },
                popExitTransition = { sharedAxisXExit(forward = false) }
            ) {
                ProfileScreen(
                    onNavigateToSettings = { navController.navigate(Settings) },
                    onAddAccount = { navController.navigate(Login) }
                )
            }

            // =================================================================
            // MEDIA DETAILS SCREEN - Shared Axis Z (Depth)
            // =================================================================
            // Navigating to detail view uses scale+fade for depth perception
            composable<MediaDetails>(
                deepLinks = listOf(
                    // Custom app scheme (for widgets, notifications, internal links)
                    navDeepLink<MediaDetails>(basePath = "anisync://details")
                ),
                // Fade only: the shared cover/title/container morph (card → page) carries the
                // spatial motion. A horizontal slide here competed with that morph — the page
                // arrived while the cover was still flying. Let the shared bounds own the movement.
                enterTransition = { fadeIn(animationSpec = effectsSpec) },
                exitTransition = { fadeOut(animationSpec = effectsSpec) },
                popEnterTransition = { fadeIn(animationSpec = effectsSpec) },
                popExitTransition = { fadeOut(animationSpec = effectsSpec) }
            ) { backStackEntry ->
                val details: MediaDetails = backStackEntry.toRoute()

                MediaDetailsScreen(
                    mediaId = details.mediaId,
                    sourceScreen = details.sourceScreen,
                    onBackClick = { navController.popBackStack() },
                    onMediaClick = { relatedId ->
                        navController.navigate(MediaDetails(relatedId, "media_details"))
                    },
                    onThemesSeeAllClick = { mediaId, mediaTitle, totalEpisodes, coverUrl ->
                        navController.navigate(MediaThemes(mediaId, mediaTitle, totalEpisodes, coverUrl))
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this
                )
            }

            // =================================================================
            // MEDIA THEMES (OPENINGS & ENDINGS) - Shared Axis Z (Depth)
            // =================================================================
            composable<MediaThemes>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) { backStackEntry ->
                val route: MediaThemes = backStackEntry.toRoute()
                MediaThemesScreen(
                    mediaTitle = route.mediaTitle,
                    totalEpisodes = route.totalEpisodes,
                    coverUrl = route.coverUrl,
                    onBackClick = { navController.popBackStack() }
                )
            }

            // =================================================================
            // AIRING CALENDAR - Shared Axis Z (Depth)
            // =================================================================
            composable<Calendar>(
                deepLinks = listOf(
                    navDeepLink { uriPattern = "anisync://calendar" }
                ),
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                CalendarScreen(
                    onBackClick = { navController.popBackStack() },
                    onMediaClick = { mediaId ->
                        navController.navigate(MediaDetails(mediaId, "calendar"))
                    }
                )
            }

            // =================================================================
            // NOTES JOURNAL - Shared Axis Z (Depth)
            // =================================================================
            composable<Notes>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                NotesJournalScreen(
                    onBackClick = { navController.popBackStack() },
                    onMediaClick = { mediaId ->
                        navController.navigate(MediaDetails(mediaId, "notes"))
                    }
                )
            }

            // =================================================================
            // SETTINGS SCREENS - Shared Axis Z (Depth)
            // =================================================================

            // Settings Hub
            composable<Settings>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                SettingsListDetail(
                    navController = navController,
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Look and Feel Settings
            composable<SettingsLookAndFeel>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                LookAndFeelScreen(
                    onBackClick = { navController.popBackStack() },
                    onNavigateToTheme = { navController.navigate(SettingsTheme) },
                    onNavigateToLanguage = { navController.navigate(SettingsLanguage) }
                )
            }

            // Theme picker (light/dark/system + AMOLED)
            composable<SettingsTheme>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                ThemeScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // App language picker
            composable<SettingsLanguage>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                LanguageScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // AniList settings (account management + AniList account options)
            composable<SettingsAniList>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                AniListSettingsScreen(
                    onLogout = {
                        navController.navigate(Login) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onBackClick = { navController.popBackStack() },
                    onAddAccount = { navController.navigate(Login) }
                )
            }

            // Notifications Settings
            composable<SettingsNotifications>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                NotificationsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Storage Settings
            composable<SettingsStorage>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                StorageScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // About Settings
            composable<SettingsAbout>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                AboutScreen(
                    onBackClick = { navController.popBackStack() },
                    onNavigateToOpenSourceLicenses = { navController.navigate(SettingsOpenSourceLicenses) },
                    onNavigateToAcknowledgments = { navController.navigate(SettingsAcknowledgments) },
                    onNavigateToLinks = { navController.navigate(SettingsLinks) }
                )
            }

            // Sponsors Settings
            composable<SettingsSponsors>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                SponsorsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Open Source Licenses
            composable<SettingsOpenSourceLicenses>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                OpenSourceLicensesScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Acknowledgments
            composable<SettingsAcknowledgments>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                AcknowledgmentsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Updates Screen
            composable<SettingsUpdates>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                UpdatesScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Links Screen
            composable<SettingsLinks>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                LinksScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Developer Tools (debug builds only — route is only navigated to from debug UI)
            composable<SettingsDeveloperTools>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                DeveloperToolsScreen(
                    onBackClick = { navController.popBackStack() },
                    onFontPlaygroundClick = { navController.navigate(SettingsFontPlayground) }
                )
            }

            // Font Playground (debug builds only — route is only navigated to from debug UI)
            composable<SettingsFontPlayground>(
                enterTransition = { sharedAxisZEnter() },
                exitTransition = { sharedAxisZExit() },
                popEnterTransition = { sharedAxisZPopEnter() },
                popExitTransition = { sharedAxisZPopExit() }
            ) {
                FontSettingsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

        }
        }
    }
}
