package com.anisync.android.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.data.AppSettings
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.Result
import com.anisync.android.domain.model.MediaType
import com.anisync.android.presentation.components.alert.ToastManager
import com.anisync.android.presentation.components.alert.ToastType
import com.anisync.android.presentation.util.LIBRARY_ALL_TAB_ID
import com.anisync.android.util.getTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.anisync.android.domain.MainTab
import com.anisync.android.domain.TabReselectBus
import com.anisync.android.domain.observeTab

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val appSettings: AppSettings,
    private val toastManager: ToastManager,
    tabReselectBus: TabReselectBus
) : ViewModel() {

    companion object {
        /** Canonical tab identifiers, in their default order. */
        val DEFAULT_TAB_IDS = listOf(
            "status:CURRENT",
            "status:PAUSED",
            "status:COMPLETED",
            "status:PLANNING",
            "status:DROPPED"
        )
    }

    private val _uiState = MutableStateFlow(
        LibraryUiState(
            mediaType = appSettings.libraryMediaType.value,
            isGridView = appSettings.libraryGridView.value,
            sortOption = runCatching { LibrarySort.valueOf(appSettings.librarySortOption.value) }
                .getOrDefault(LibrarySort.AIRING_SOON),
            isAscending = appSettings.librarySortAscending.value
        )
    )
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        tabReselectBus.observeTab(
            tab = MainTab.LIBRARY,
            scope = viewModelScope,
            onScrollToTop = { _uiState.update { it.copy(scrollToTopRequest = it.scrollToTopRequest + 1) } },
            onSearch = { _uiState.update { it.copy(searchOverlayRequest = it.searchOverlayRequest + 1) } }
        )
    }

    private val _actions = MutableSharedFlow<LibraryAction>()
    val actions: SharedFlow<LibraryAction> = _actions.asSharedFlow()

    private var hasLoadedInitially = false
    private var hasRestoredTab = false

    private data class LibraryComputed(
        val allEntries: List<LibraryEntry>,
        val grouped: Map<LibraryStatus, List<LibraryEntry>>,
        val hiddenListNames: Set<String>,
        val tabOrder: List<String>,
        val tabCounts: Map<String, Int>,
        val searchMatches: List<LibraryEntry>,
        val searchMatchesByCategory: Map<String, List<LibraryEntry>>
    )

    init {
        appSettings.titleLanguage.onEach { lang ->
            _uiState.update { it.copy(titleLanguage = lang) }
        }.launchIn(viewModelScope)
        
        appSettings.showScoreOnCards.onEach { show ->
            _uiState.update { it.copy(showScoreOnCards = show) }
        }.launchIn(viewModelScope)

        appSettings.libraryGridView.onEach { isGrid ->
            _uiState.update { it.copy(isGridView = isGrid) }
        }.launchIn(viewModelScope)

        observeLibraryData()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.OnScreenVisible -> onScreenVisible()
            is LibraryAction.Refresh -> refresh()
            is LibraryAction.OnMediaTypeChange -> {
                hasRestoredTab = false
                appSettings.setLibraryMediaType(action.type)
                _uiState.update {
                    it.copy(
                        mediaType = action.type,
                        isLoading = true,
                        errorMessage = null,
                        initialTabId = null,
                        activeSearchCategory = LIBRARY_ALL_TAB_ID,
                        // A selection belongs to the list it was made in.
                        selectedEntryIds = emptySet(),
                        selectionTabId = null
                    )
                }
            }

            is LibraryAction.OnSortOptionChange -> {
                appSettings.setLibrarySort(action.sort.name, action.ascending)
                _uiState.update {
                    it.copy(
                        sortOption = action.sort,
                        isAscending = action.ascending
                    )
                }
            }

            is LibraryAction.OnSearchQueryChange -> {
                _uiState.update {
                    it.copy(
                        searchQuery = action.query,
                        // A blank query resets the category chips back to "All".
                        activeSearchCategory = if (action.query.isBlank()) LIBRARY_ALL_TAB_ID else it.activeSearchCategory
                    )
                }
            }

            is LibraryAction.OnSearchCategoryChange -> {
                _uiState.update { it.copy(activeSearchCategory = action.categoryId) }
            }

            is LibraryAction.OnSearchOpened -> {
                // Seed the category to the tab search was opened from (the "search this list" case);
                // the UI falls back to "All" if that list has no matches for the current query.
                _uiState.update { it.copy(activeSearchCategory = action.currentTabId) }
            }

            is LibraryAction.IncrementProgress -> updateProgress(action.mediaId, 1)
            is LibraryAction.DecrementProgress -> updateProgress(action.mediaId, -1)
            is LibraryAction.UpdateEntry -> updateEntry(action.entry)
            is LibraryAction.DeleteEntry -> deleteEntry(action.entry)
            is LibraryAction.ToggleListVisibility -> toggleListVisibility(
                action.listName,
                action.hidden
            )

            is LibraryAction.ReorderTabs -> reorderTabs(action.tabOrder)
            is LibraryAction.SetGridView -> appSettings.setLibraryGridView(action.isGrid)

            is LibraryAction.OnTabSelected -> {
                // A selection belongs to the list it was started in; leaving that list ends it.
                if (_uiState.value.selectionTabId != null &&
                    _uiState.value.selectionTabId != action.tabId
                ) {
                    clearSelection()
                }
                saveSelectedTab(action.tabId)
            }

            is LibraryAction.ConsumeInitialTab -> _uiState.update { it.copy(initialTabId = null) }

            is LibraryAction.EnterSelection -> _uiState.update {
                it.copy(selectionTabId = action.tabId, selectedEntryIds = setOf(action.entryId))
            }

            is LibraryAction.ToggleSelection -> _uiState.update { st ->
                val next = if (action.entryId in st.selectedEntryIds) {
                    st.selectedEntryIds - action.entryId
                } else {
                    st.selectedEntryIds + action.entryId
                }
                // Unticking the last row leaves selection mode, matching every other list app.
                if (next.isEmpty()) {
                    st.copy(selectedEntryIds = emptySet(), selectionTabId = null)
                } else {
                    st.copy(selectedEntryIds = next)
                }
            }

            is LibraryAction.SelectAll -> _uiState.update {
                it.copy(selectedEntryIds = action.entryIds.toSet())
            }

            is LibraryAction.ClearSelection -> clearSelection()

            is LibraryAction.BulkSetStatus -> bulkUpdate { it.copy(status = action.status) }
            is LibraryAction.BulkSetScore -> bulkUpdate { it.copy(score = action.score) }
            is LibraryAction.BulkRemove -> bulkRemove()
            is LibraryAction.CancelBulkOperation -> bulkJob?.cancel()
        }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedEntryIds = emptySet(), selectionTabId = null) }
    }

    /** The selected entries, in list order. */
    private fun selectedEntries(): List<LibraryEntry> {
        val ids = _uiState.value.selectedEntryIds
        if (ids.isEmpty()) return emptyList()
        return _uiState.value.entries.filter { it.mediaId in ids }
    }

    private var bulkJob: kotlinx.coroutines.Job? = null

    /** Saves [change] applied to every selected entry, one request each. */
    private fun bulkUpdate(change: (LibraryEntry) -> LibraryEntry) {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        runBulk(BulkKind.UPDATE, entries, { libraryRepository.updateEntry(change(it)) }) { count ->
            "Updated $count ${entryWord(count)}"
        }
    }

    private fun bulkRemove() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        runBulk(BulkKind.REMOVE, entries, { libraryRepository.deleteEntry(it) }) { count ->
            "Removed $count ${entryWord(count)}"
        }
    }

    /**
     * Runs [step] for each entry in turn, reporting progress. Stops at the first failure, since the
     * rest would most likely fail the same way (offline, signed out).
     */
    private fun runBulk(
        kind: BulkKind,
        entries: List<LibraryEntry>,
        step: suspend (LibraryEntry) -> Result<*>,
        message: (Int) -> String
    ) {
        _uiState.update { it.copy(bulkOperation = BulkOperation(kind, done = 0, total = entries.size)) }
        bulkJob = viewModelScope.launch {
            try {
                var done = 0
                var failure: Result.Error? = null
                for (entry in entries) {
                    val result = step(entry)
                    if (result is Result.Error) {
                        failure = result
                        break
                    }
                    done++
                    _uiState.update { st -> st.copy(bulkOperation = st.bulkOperation?.copy(done = done)) }
                }
                onBulkFinished(failure ?: Result.Success(done), message)
            } finally {
                _uiState.update { it.copy(bulkOperation = null) }
            }
        }
    }

    private fun onBulkFinished(result: Result<Int>, message: (Int) -> String) {
        when (result) {
            is Result.Success -> {
                clearSelection()
                toastManager.showToast(ToastType.SUCCESS, message = message(result.data))
            }

            is Result.Error -> showResultError(result)
        }
    }

    private fun entryWord(count: Int) = if (count == 1) "entry" else "entries"

    private fun observeLibraryData() {
        viewModelScope.launch {
            _uiState
                .map { it.mediaType }
                .distinctUntilChanged()
                .flatMapLatest { type ->
                    combine(
                        libraryRepository.observeLibrary(type),
                        appSettings.libraryListOrder,
                        appSettings.hiddenLibraryLists
                    ) { libraryEntries, listOrder, hiddenLists ->
                        libraryEntries to (listOrder to hiddenLists)
                    }
                }
                .combine(
                    _uiState.map { state ->
                        listOf(
                            state.sortOption,
                            state.isAscending,
                            state.searchQuery,
                            state.titleLanguage
                        )
                    }.distinctUntilChanged()
                ) { (entries, listPrefs), combinedState ->
                    val sort = combinedState[0] as LibrarySort
                    val ascending = combinedState[1] as Boolean
                    val query = combinedState[2] as String
                    val titleLang = combinedState[3] as com.anisync.android.data.TitleLanguage
                    val (listOrder, hiddenLists) = listPrefs

                    // No early return for an empty library: the sort/group/count logic below all
                    // collapses to empties cleanly.
                    class SortableEntry(val entry: LibraryEntry, val sortTitle: String)

                    val sortableEntries =
                        entries.map { SortableEntry(it, it.getTitle(titleLang).lowercase()) }

                    val titleDir = if (ascending) 1 else -1
                    val keyDir = -titleDir
                    val titleCmp = Comparator<SortableEntry> { a, b ->
                        a.sortTitle.compareTo(b.sortTitle) * titleDir
                    }
                    fun <K : Comparable<K>> primaryDesc(key: (SortableEntry) -> K?): Comparator<SortableEntry> =
                        Comparator { a, b ->
                            val ka = key(a); val kb = key(b)
                            val cmp = when {
                                ka == null && kb == null -> 0
                                ka == null -> 1
                                kb == null -> -1
                                else -> ka.compareTo(kb)
                            }
                            if (cmp != 0) cmp * keyDir else titleCmp.compare(a, b)
                        }

                    val sortedEntries = when (sort) {
                        LibrarySort.TITLE -> sortableEntries.sortedWith(titleCmp)
                        LibrarySort.PROGRESS -> sortableEntries.sortedWith(primaryDesc { it.entry.progress })
                        LibrarySort.AIRING_SOON -> sortableEntries.sortedWith(
                            Comparator { a, b ->
                                val ka = a.entry.nextAiringEpisodeTime
                                val kb = b.entry.nextAiringEpisodeTime
                                val cmp = when {
                                    ka == null && kb == null -> 0
                                    ka == null -> 1
                                    kb == null -> -1
                                    else -> ka.compareTo(kb)
                                }
                                val withDir = if (ascending) cmp else -cmp
                                if (withDir != 0) withDir else titleCmp.compare(a, b)
                            }
                        )
                        LibrarySort.SCORE -> sortableEntries.sortedWith(primaryDesc { it.entry.score })
                        LibrarySort.LAST_UPDATED -> sortableEntries.sortedWith(primaryDesc { it.entry.updatedAt })
                        LibrarySort.LAST_ADDED -> sortableEntries.sortedWith(primaryDesc { it.entry.createdAt })
                        LibrarySort.START_DATE -> sortableEntries.sortedWith(primaryDesc { it.entry.startedAt })
                    }

                    val allEntries = sortedEntries.map { it.entry }
                    val grouped = allEntries.groupBy { it.status }

                    val tabOrder = buildTabOrder(listOrder)

                    // Raw per-tab counts (independent of the query) for the tab badges.
                    val tabCounts = buildMap {
                        put(LIBRARY_ALL_TAB_ID, allEntries.size)
                        grouped.forEach { (status, list) -> put("status:${status.name}", list.size) }
                    }

                    // Search matches the title and notes, grouped by list so the overlay can offer
                    // category chips.
                    val trimmedQuery = query.trim()
                    val searchMatches: List<LibraryEntry>
                    val searchMatchesByCategory: Map<String, List<LibraryEntry>>
                    if (trimmedQuery.isBlank()) {
                        searchMatches = emptyList()
                        searchMatchesByCategory = emptyMap()
                    } else {
                        val lowerQuery = trimmedQuery.lowercase()
                        val matches = allEntries.filter { it.matchesQuery(lowerQuery) }
                        val byCategory = LinkedHashMap<String, List<LibraryEntry>>()
                        matches.groupBy { it.status }.forEach { (status, list) ->
                            byCategory["status:${status.name}"] = list
                        }
                        searchMatches = matches
                        searchMatchesByCategory = byCategory
                    }

                    LibraryComputed(
                        allEntries = allEntries,
                        grouped = grouped,
                        hiddenListNames = hiddenLists,
                        tabOrder = tabOrder,
                        tabCounts = tabCounts,
                        searchMatches = searchMatches,
                        searchMatchesByCategory = searchMatchesByCategory
                    )
                }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.message ?: "Unknown error"
                        )
                    }
                }
                .collect { computed ->
                    // On first emission, resolve saved tab with fallback ("all" is always visible).
                    val resolvedInitialTab = if (!hasRestoredTab) {
                        hasRestoredTab = true
                        val savedTabId = appSettings.lastSelectedLibraryTab.value
                        val visibleTabs = computed.tabOrder.filter { it !in computed.hiddenListNames }
                        when {
                            savedTabId != null && savedTabId in visibleTabs -> savedTabId
                            // With nothing saved, default to Watching rather than the first tab ("All").
                            "status:CURRENT" in visibleTabs -> "status:CURRENT"
                            else -> null
                        }
                    } else {
                        null
                    }

                    _uiState.update {
                        it.copy(
                            entries = computed.allEntries,
                            groupedEntries = computed.grouped,
                            hiddenListNames = computed.hiddenListNames,
                            tabOrder = computed.tabOrder,
                            tabCounts = computed.tabCounts,
                            searchMatches = computed.searchMatches,
                            searchMatchesByCategory = computed.searchMatchesByCategory,
                            initialTabId = resolvedInitialTab ?: it.initialTabId,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
        }
    }

    private fun onScreenVisible() {
        if (!hasLoadedInitially) {
            hasLoadedInitially = true
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            when (val result = libraryRepository.refreshLibrary()) {
                is Result.Success -> {} // Automatically updated via Flow
                is Result.Error -> showResultError(result)
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    // Coalesces rapid +/- taps on the same media into one save of the settled value
    // (rather than one network save per tap). Each tap updates the UI
    // optimistically across every list the entry appears in; a +1 then −1 nets to a
    // no-op. On failure the optimistic value rolls back to the last known-good one.
    private val progressBaseline = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val progressCoalescer =
        com.anisync.android.presentation.util.MutationCoalescer<Int, Int>(viewModelScope, debounceMs = 600L) { mediaId, progress ->
            when (val result = libraryRepository.updateProgress(mediaId, progress)) {
                is Result.Success -> {
                    progressBaseline[mediaId] = progress
                    true
                }
                is Result.Error -> {
                    progressBaseline[mediaId]?.let { patchEntryProgress(mediaId, it) }
                    showResultError(result)
                    false
                }
            }
        }

    private fun updateProgress(mediaId: Int, delta: Int) {
        val entry = _uiState.value.entries.find { it.mediaId == mediaId } ?: return
        if (!entry.type.hasEditableProgress) return
        progressCoalescer.seed(mediaId, entry.progress)
        progressBaseline.putIfAbsent(mediaId, entry.progress)
        // Games count minutes; a tap there is half an hour, as on the web.
        val step = if (entry.type == MediaType.GAME) 30 else 1
        val newProgress = (entry.progress + delta * step).coerceAtLeast(0)
        patchEntryProgress(mediaId, newProgress)
        progressCoalescer.submit(mediaId, newProgress)
    }

    /** Optimistically set [mediaId]'s progress across every list it appears in. */
    private fun patchEntryProgress(mediaId: Int, newProgress: Int) {
        fun List<LibraryEntry>.patched(): List<LibraryEntry> =
            map { if (it.mediaId == mediaId) it.copy(progress = newProgress) else it }
        _uiState.update { st ->
            st.copy(
                entries = st.entries.patched(),
                groupedEntries = st.groupedEntries.mapValues { it.value.patched() }
            )
        }
    }

    private fun updateEntry(entry: LibraryEntry) {
        viewModelScope.launch {
            when (val result = libraryRepository.updateEntry(entry)) {
                is Result.Success -> toastManager.showToast(ToastType.SUCCESS, message = "Entry updated")
                is Result.Error -> showResultError(result)
            }
        }
    }

    private fun deleteEntry(entry: LibraryEntry) {
        viewModelScope.launch {
            when (val result = libraryRepository.deleteEntry(entry)) {
                is Result.Success -> toastManager.showToast(ToastType.SUCCESS, message = "Entry removed")
                is Result.Error -> showResultError(result)
            }
        }
    }

    private fun toggleListVisibility(listName: String, hidden: Boolean) {
        val current = _uiState.value.hiddenListNames.toMutableSet()
        if (hidden) current.add(listName) else current.remove(listName)
        appSettings.setHiddenLibraryLists(current)
    }

    /** Saves the new full tab order after a drag-to-reorder operation. */
    private fun reorderTabs(newOrder: List<String>) {
        appSettings.setLibraryListOrder(newOrder)
    }

    /** Persists the selected tab; one choice for every media type, since the tabs are the same. */
    private fun saveSelectedTab(tabId: String) {
        appSettings.setLastSelectedLibraryTab(tabId)
    }

    /**
     * The stored tab order, keeping only tabs that still exist and appending any it lacks, with
     * "All" first unless the user moved it.
     */
    private fun buildTabOrder(storedOrder: List<String>): List<String> {
        val known = DEFAULT_TAB_IDS.toSet() + LIBRARY_ALL_TAB_ID
        val result = storedOrder.filter { it in known }.distinct().toMutableList()
        for (tab in DEFAULT_TAB_IDS) if (tab !in result) result.add(tab)
        return if (LIBRARY_ALL_TAB_ID in result) result else listOf(LIBRARY_ALL_TAB_ID) + result
    }

    private fun showResultError(result: Result.Error) {
        toastManager.showResultError(result)
    }
}

/** Case-insensitive match of [lowerQuery] against the title and the entry's notes (#75). */
private fun LibraryEntry.matchesQuery(lowerQuery: String): Boolean =
    title.contains(lowerQuery, ignoreCase = true) ||
        notes?.contains(lowerQuery, ignoreCase = true) == true