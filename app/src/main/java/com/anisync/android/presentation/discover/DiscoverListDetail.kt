package com.anisync.android.presentation.discover

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.anisync.android.presentation.navigation.Calendar
import com.anisync.android.presentation.navigation.LIST_DETAIL_PANE_SOURCE
import com.anisync.android.presentation.navigation.MediaDetails
import com.anisync.android.presentation.navigation.PaneDetailHost
import com.anisync.android.presentation.navigation.SectionGrid
import com.anisync.android.presentation.navigation.Settings
import com.anisync.android.presentation.navigation.TwoPaneListDetailScaffold
import com.anisync.android.presentation.util.LocalAdaptiveInfo

/**
 * The Discover tab. Compact/medium widths show the plain [DiscoverScreen] and push the full-screen
 * detail. Expanded widths use the shared two-pane [TwoPaneListDetailScaffold] — the discover feed as
 * the permanent list pane, the selected media's detail in the on-demand resizable pane.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DiscoverListDetail(
    navController: NavHostController,
    // sourceSection = the TransitionKeys.DISCOVER_* prefix of the tapped section; becomes
    // MediaDetails.sourceScreen so the return morph targets the exact card tapped.
    onMediaClickFullScreen: (mediaId: Int, sourceSection: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val feed: @Composable (onMediaClick: (Int, String) -> Unit) -> Unit = { onMediaClick ->
        DiscoverScreen(
            navController = navController,
            onMediaClick = onMediaClick,
            onSectionSeeAllClick = { title, sectionType, mediaType ->
                navController.navigate(SectionGrid(title, sectionType, mediaType.name))
            },
            onNavigateToCalendar = { navController.navigate(Calendar) },
            onNavigateToSettings = { navController.navigate(Settings) },
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }

    if (!LocalAdaptiveInfo.current.supportsTwoPane) {
        feed(onMediaClickFullScreen)
        return
    }

    // Discover section items don't show a selected state, so the pane's selected id is ignored here.
    // The pane detail uses its own LIST_DETAIL_PANE_SOURCE prefix (no cross-pane morph), so the
    // tapped section is dropped.
    TwoPaneListDetailScaffold(
        listPane = { _, onItemClick ->
            feed { mediaId, _ -> onItemClick(mediaId) }
        },
        detailPane = { mediaId, onClose ->
            PaneDetailHost(
                startRoute = MediaDetails(mediaId, LIST_DETAIL_PANE_SOURCE),
                navController = navController,
                onClose = onClose,
            )
        },
    )
}
