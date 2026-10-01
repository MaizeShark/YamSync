package com.anisync.android.presentation.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.anisync.android.data.TitleLanguage
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.ScoreFormat
import com.anisync.android.presentation.util.LIBRARY_ALL_TAB_ID
import com.anisync.android.domain.model.MediaType

/** Which batch operation is running. Yamtrack has no batch endpoint, so each costs one request per entry. */
enum class BulkKind { UPDATE, REMOVE }

/** Progress of a running batch. */
@Immutable
data class BulkOperation(
    val kind: BulkKind,
    val done: Int,
    val total: Int
)

@Stable
data class LibraryUiState(
    val mediaType: MediaType = MediaType.ANIME,
    val sortOption: LibrarySort = LibrarySort.AIRING_SOON,
    val isAscending: Boolean = true,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    /** Bumped when the Library tab is reselected, asking the open list to scroll to the top. */
    val scrollToTopRequest: Long = 0L,
    /** Bumped when the tab is double-tapped, asking the search bar to expand. */
    val searchOverlayRequest: Long = 0L,
    val titleLanguage: TitleLanguage = TitleLanguage.ROMAJI,
    /** Yamtrack scores are always 0–10 with one decimal. */
    val userScoreFormat: ScoreFormat = ScoreFormat.POINT_10_DECIMAL,
    /** Every visible entry across all status lists, sorted — feeds the synthetic "All" tab. */
    val entries: List<LibraryEntry> = emptyList(),
    val groupedEntries: Map<LibraryStatus, List<LibraryEntry>> = emptyMap(),
    val hiddenListNames: Set<String> = emptySet(),
    val tabOrder: List<String> = emptyList(),
    /** Raw entry count per tab id (incl. [LIBRARY_ALL_TAB_ID]); unaffected by [searchQuery]. */
    val tabCounts: Map<String, Int> = emptyMap(),
    /** Flat list of all entries matching [searchQuery] (across every status list). */
    val searchMatches: List<LibraryEntry> = emptyList(),
    /** Query matches grouped by tab id (status ids); non-empty only. */
    val searchMatchesByCategory: Map<String, List<LibraryEntry>> = emptyMap(),
    /** The search category chip currently selected; [LIBRARY_ALL_TAB_ID] shows everything. */
    val activeSearchCategory: String = LIBRARY_ALL_TAB_ID,
    val showScoreOnCards: Boolean = true,
    /** Poster grid (true) or single-column rows. One choice for every list, not one per list. */
    val isGridView: Boolean = true,
    /** Local media ids ([LibraryEntry.mediaId]) in the current selection. */
    val selectedEntryIds: Set<Int> = emptySet(),
    /** The tab the selection was started in. Selection never spans lists. */
    val selectionTabId: String? = null,
    val bulkOperation: BulkOperation? = null,
    val initialTabId: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
) {
    val isSelectionMode: Boolean get() = selectionTabId != null
}

enum class LibrarySort {
    TITLE,
    PROGRESS,
    AIRING_SOON,
    SCORE,
    LAST_UPDATED,
    LAST_ADDED,
    START_DATE
}

sealed interface LibraryAction {
    data object OnScreenVisible : LibraryAction
    data object Refresh : LibraryAction
    data class OnMediaTypeChange(val type: MediaType) : LibraryAction
    data class OnSortOptionChange(val sort: LibrarySort, val ascending: Boolean) : LibraryAction
    data class OnSearchQueryChange(val query: String) : LibraryAction
    data class OnSearchCategoryChange(val categoryId: String) : LibraryAction
    data class OnSearchOpened(val currentTabId: String) : LibraryAction
    data class IncrementProgress(val mediaId: Int) : LibraryAction
    data class DecrementProgress(val mediaId: Int) : LibraryAction
    data class UpdateEntry(val entry: LibraryEntry) : LibraryAction
    data class DeleteEntry(val entry: LibraryEntry) : LibraryAction
    data class ToggleListVisibility(val listName: String, val hidden: Boolean) : LibraryAction
    data class ReorderTabs(val tabOrder: List<String>) : LibraryAction
    data class SetGridView(val isGrid: Boolean) : LibraryAction
    data class OnTabSelected(val tabId: String) : LibraryAction
    data object ConsumeInitialTab : LibraryAction

    /** Long-press on a row. Starts selection in [tabId] with [entryId] (a media id) already ticked. */
    data class EnterSelection(val entryId: Int, val tabId: String) : LibraryAction
    data class ToggleSelection(val entryId: Int) : LibraryAction
    data class SelectAll(val entryIds: List<Int>) : LibraryAction
    data object ClearSelection : LibraryAction

    /** One request per entry. Reports progress and can be cancelled. */
    data class BulkSetStatus(val status: LibraryStatus) : LibraryAction
    data class BulkSetScore(val score: Double) : LibraryAction
    data object BulkRemove : LibraryAction
    data object CancelBulkOperation : LibraryAction
}
