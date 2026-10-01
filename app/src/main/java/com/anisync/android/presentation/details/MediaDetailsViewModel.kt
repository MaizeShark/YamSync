package com.anisync.android.presentation.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaDetails
import com.anisync.android.domain.MediaKeyRegistry
import com.anisync.android.domain.MediaRepository
import com.anisync.android.domain.MediaSummary
import com.anisync.android.domain.Result
import com.anisync.android.presentation.components.alert.ToastManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the details page knows about its item. */
data class DetailsUiState(
    /** Known before the page loads: the title and image last seen for the item. */
    val summary: MediaSummary? = null,
    val details: MediaDetails? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** True while a write to the library is in flight. */
    val isSaving: Boolean = false,
    /** Episode numbers being marked watched right now. */
    val markingEpisodes: Set<Int> = emptySet()
)

@HiltViewModel
class MediaDetailsViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val libraryRepository: LibraryRepository,
    private val registry: MediaKeyRegistry,
    private val toastManager: ToastManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val mediaId: Int = checkNotNull(savedStateHandle["mediaId"]) {
        "Media ID is required for MediaDetailsViewModel"
    }

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    /** The user's entry for the item, live from the library cache; null when not tracked. */
    val entry: StateFlow<LibraryEntry?> = libraryRepository.observeEntry(mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editing = MutableStateFlow<LibraryEntry?>(null)
    /** The entry open in the editor, or null when the editor is closed. */
    val editing: StateFlow<LibraryEntry?> = _editing.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(summary = registry.summary(mediaId)) }
        }
        load(isRefresh = false)
    }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = isRefresh, errorMessage = null) }
            when (val result = mediaRepository.details(mediaId)) {
                is Result.Success -> _uiState.update {
                    it.copy(details = result.data, isLoading = false, isRefreshing = false)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, errorMessage = result.message)
                }
            }
        }
    }

    /** Adds the item with [status], or moves the existing entry to it. */
    fun setStatus(status: LibraryStatus) {
        val current = entry.value
        if (current == null) {
            write {
                val summary = _uiState.value.summary
                val details = _uiState.value.details
                val key = details?.key ?: summary?.key ?: return@write Result.Error("Unknown item")
                libraryRepository.addEntry(key, status, details?.title ?: summary?.title, details?.imageUrl ?: summary?.imageUrl)
            }
        } else if (current.status != status) {
            val now = System.currentTimeMillis()
            write {
                libraryRepository.updateEntry(
                    current.copy(
                        status = status,
                        startedAt = current.startedAt ?: now.takeIf { status == LibraryStatus.CURRENT },
                        completedAt = current.completedAt ?: now.takeIf { status == LibraryStatus.COMPLETED }
                    )
                )
            }
        }
    }

    fun setProgress(progress: Int) = write { libraryRepository.updateProgress(mediaId, progress) }

    fun openEditor() {
        _editing.value = entry.value
    }

    fun closeEditor() {
        _editing.value = null
    }

    fun save(edited: LibraryEntry) = write(closeEditor = true) { libraryRepository.updateEntry(edited) }

    fun delete() {
        val current = entry.value ?: return
        write(closeEditor = true) { libraryRepository.deleteEntry(current) }
    }

    /** Starts another watch/read: a fresh entry, the finished one kept as history. */
    fun addRewatch() {
        val current = entry.value ?: return
        write(closeEditor = true) { libraryRepository.addRewatch(current) }
    }

    fun markEpisodeWatched(episodeNumber: Int) {
        if (episodeNumber in _uiState.value.markingEpisodes) return
        viewModelScope.launch {
            _uiState.update { it.copy(markingEpisodes = it.markingEpisodes + episodeNumber) }
            when (val result = mediaRepository.markEpisodeWatched(mediaId, episodeNumber)) {
                is Result.Success -> load(isRefresh = true)
                is Result.Error -> toastManager.showResultError(result)
            }
            _uiState.update { it.copy(markingEpisodes = it.markingEpisodes - episodeNumber) }
        }
    }

    private fun write(closeEditor: Boolean = false, block: suspend () -> Result<*>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = block()) {
                is Result.Success -> if (closeEditor) _editing.value = null
                is Result.Error -> toastManager.showResultError(result)
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
