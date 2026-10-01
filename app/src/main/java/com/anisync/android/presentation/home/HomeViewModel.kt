package com.anisync.android.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.domain.AiringEpisode
import com.anisync.android.domain.CalendarRepository
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MainTab
import com.anisync.android.domain.Result
import com.anisync.android.domain.TabReselectBus
import com.anisync.android.domain.observeTab
import com.anisync.android.presentation.components.alert.ToastManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Home tab: what the user is in the middle of, and what comes out next. Mirrors Yamtrack's own
 * home page, across every tracked type.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val calendarRepository: CalendarRepository,
    private val toastManager: ToastManager,
    tabReselectBus: TabReselectBus
) : ViewModel() {

    /** In-progress entries, most recently touched first. */
    val inProgress: StateFlow<List<LibraryEntry>> = libraryRepository.observeLibrary()
        .map { entries ->
            entries.filter { it.status == LibraryStatus.CURRENT }
                .sortedByDescending { it.updatedAt ?: it.createdAt ?: 0L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _upcoming = MutableStateFlow<List<AiringEpisode>>(emptyList())
    /** Releases in the next week. */
    val upcoming: StateFlow<List<AiringEpisode>> = _upcoming.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _scrollToTop = MutableStateFlow(0L)
    val scrollToTop: StateFlow<Long> = _scrollToTop.asStateFlow()

    /** Entries whose progress is being saved right now, so their button can't be tapped twice. */
    private val _saving = MutableStateFlow<Set<Int>>(emptySet())
    val saving: StateFlow<Set<Int>> = _saving.asStateFlow()

    init {
        tabReselectBus.observeTab(MainTab.HOME, viewModelScope, onScrollToTop = { _scrollToTop.update { it + 1 } })
        loadUpcoming()
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val library = async { libraryRepository.refreshLibrary() }
            val calendar = async { calendarRepository.sync() }
            (library.await() as? Result.Error)?.let { toastManager.showResultError(it) }
            calendar.await()
            loadUpcoming()
            _isRefreshing.value = false
        }
    }

    private fun loadUpcoming() {
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val result = calendarRepository.getWeekSchedule(now - 3600, now + WEEK_SECONDS)
            if (result is Result.Success) _upcoming.value = result.data
        }
    }

    /** Steps progress by one unit (half an hour for games). */
    fun increment(entry: LibraryEntry) {
        if (entry.mediaId in _saving.value) return
        val step = if (entry.type.progressUnit == com.anisync.android.domain.model.ProgressUnit.MINUTES) 30 else 1
        viewModelScope.launch {
            _saving.update { it + entry.mediaId }
            val result = libraryRepository.updateProgress(entry.mediaId, entry.progress + step)
            if (result is Result.Error) toastManager.showResultError(result)
            _saving.update { it - entry.mediaId }
        }
    }

    private companion object {
        const val WEEK_SECONDS = 7 * 24 * 3600L
    }
}
