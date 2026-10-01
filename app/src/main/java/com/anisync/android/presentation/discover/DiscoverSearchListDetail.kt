package com.anisync.android.presentation.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.navigation.NavHostController
import com.anisync.android.presentation.navigation.MediaDetails
import com.anisync.android.presentation.navigation.PaneDetailHost

private const val SEARCH_PANE_SOURCE = "discover_search"

/**
 * What a tapped search result opens in the wide Discover-search detail pane, reusing the shared
 * in-pane media graph.
 */
sealed interface SearchTarget {
    data class Media(val id: Int) : SearchTarget
}

internal fun SearchTarget.toPaneRoute(): Any = when (this) {
    is SearchTarget.Media -> MediaDetails(id, SEARCH_PANE_SOURCE)
}

/** Persists the open search target across configuration changes (for the scaffold's selection). */
internal val SearchTargetSaver = listSaver<SearchTarget?, Any>(
    save = { target ->
        when (target) {
            is SearchTarget.Media -> listOf("media", target.id)
            null -> emptyList()
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "media" -> SearchTarget.Media(saved[1] as Int)
            else -> null
        }
    },
)

/**
 * Detail pane for the wide Discover-search two-pane: hosts the tapped [target]'s screen in a
 * self-contained [PaneDetailHost], reusing the shared media graph (relations and "see all" grids drill
 * WITHIN the pane; cross-feature destinations escalate to the app [navController]).
 */
@Composable
fun SearchDetailPane(
    target: SearchTarget,
    navController: NavHostController,
    onClose: () -> Unit,
) {
    PaneDetailHost(
        startRoute = target.toPaneRoute(),
        navController = navController,
        onClose = onClose,
    )
}
