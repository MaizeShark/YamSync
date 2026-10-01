package com.anisync.android.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.data.AppSettings
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaCard
import com.anisync.android.domain.MediaRepository
import com.anisync.android.domain.Result
import com.anisync.android.domain.model.MediaType
import com.anisync.android.presentation.components.alert.ToastManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val type: MediaType = MediaType.ANIME,
    /** Null searches the type's default source. */
    val source: String? = null,
    val results: List<MediaCard> = emptyList(),
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** True once a search has run for the current query, so "no results" can be told from "not yet". */
    val hasSearched: Boolean = false,
    /** Items being added right now. */
    val adding: Set<Int> = emptySet()
)

/** Searches the metadata provider Yamtrack uses for each type, through the user's server. */
@HiltViewModel
@OptIn(FlowPreview::class)
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val libraryRepository: LibraryRepository,
    private val appSettings: AppSettings,
    private val toastManager: ToastManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(type = appSettings.lastSearchType.value))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** What the user already has, to mark results they track. */
    val statuses: StateFlow<Map<Int, LibraryStatus>> = libraryRepository.observeListStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        scheduleSearch(debounce = true)
    }

    fun onTypeChange(type: MediaType) {
        if (type == _uiState.value.type) return
        appSettings.setLastSearchType(type)
        _uiState.update { it.copy(type = type, source = null) }
        scheduleSearch(debounce = false)
    }

    fun onSourceChange(source: String) {
        _uiState.update { it.copy(source = source.takeIf { s -> s != it.type.defaultSource }) }
        scheduleSearch(debounce = false)
    }

    fun submit() = scheduleSearch(debounce = false)

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasNextPage) return
        searchJob = viewModelScope.launch { run(state.page + 1) }
    }

    /** Adds a result to the library as planned. */
    fun addToPlanning(card: MediaCard) {
        if (card.mediaId in _uiState.value.adding) return
        viewModelScope.launch {
            _uiState.update { it.copy(adding = it.adding + card.mediaId) }
            val result = libraryRepository.addEntry(card.key, LibraryStatus.PLANNING, card.title, card.imageUrl)
            if (result is Result.Error) toastManager.showResultError(result)
            _uiState.update { it.copy(adding = it.adding - card.mediaId) }
        }
    }

    private fun scheduleSearch(debounce: Boolean) {
        searchJob?.cancel()
        if (_uiState.value.query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), page = 0, hasNextPage = false, hasSearched = false, errorMessage = null, isLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Each search goes out to the provider through the server, so typing waits for a pause.
            if (debounce) delay(DEBOUNCE_MS)
            run(page = 1)
        }
    }

    private suspend fun run(page: Int) {
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = mediaRepository.search(state.type, state.query.trim(), state.source, page)) {
            is Result.Success -> _uiState.update {
                it.copy(
                    results = if (page == 1) result.data.results else (it.results + result.data.results).distinctBy { c -> c.key },
                    page = result.data.page,
                    hasNextPage = result.data.hasNextPage,
                    isLoading = false,
                    hasSearched = true
                )
            }
            is Result.Error -> _uiState.update {
                it.copy(isLoading = false, errorMessage = result.message, hasSearched = true)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
