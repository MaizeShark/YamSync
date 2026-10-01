package com.anisync.android.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.data.account.Account
import com.anisync.android.data.account.AccountManager
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MainTab
import com.anisync.android.domain.Result
import com.anisync.android.domain.TabReselectBus
import com.anisync.android.domain.model.MediaType
import com.anisync.android.domain.model.ProgressUnit
import com.anisync.android.domain.observeTab
import com.anisync.android.presentation.components.alert.ToastManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One media type's share of the library. */
data class TypeStats(
    val type: MediaType,
    val total: Int,
    val byStatus: Map<LibraryStatus, Int>,
    /** Mean of the scored entries, 0–10; null when nothing is scored. */
    val meanScore: Double?,
    /** Sum of progress in the type's unit (episodes, chapters…, minutes for games). */
    val totalProgress: Int
)

data class LibraryStats(
    val total: Int = 0,
    val byStatus: Map<LibraryStatus, Int> = emptyMap(),
    val meanScore: Double? = null,
    val byType: List<TypeStats> = emptyList()
)

/**
 * The signed-in account and what its library adds up to. Yamtrack has no public profile to show, so
 * the numbers are worked out from the synced library on the device.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val libraryRepository: LibraryRepository,
    private val toastManager: ToastManager,
    tabReselectBus: TabReselectBus
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountManager.accounts
    val activeAccount: StateFlow<Account?> = accountManager.activeAccount

    val stats: StateFlow<LibraryStats> = libraryRepository.observeLibrary()
        .map(::computeStats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _scrollToTop = MutableStateFlow(0L)
    val scrollToTop: StateFlow<Long> = _scrollToTop.asStateFlow()

    init {
        tabReselectBus.observeTab(MainTab.PROFILE, viewModelScope, onScrollToTop = { _scrollToTop.update { it + 1 } })
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = libraryRepository.refreshLibrary()
            if (result is Result.Error) toastManager.showResultError(result)
            _isRefreshing.value = false
        }
    }

    fun switchAccount(id: Int) = accountManager.switch(id)

    fun logout() = accountManager.logoutActive()

    private fun computeStats(entries: List<LibraryEntry>): LibraryStats {
        fun mean(list: List<LibraryEntry>) = list.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()
        val byType = entries.groupBy { it.type }.map { (type, list) ->
            TypeStats(
                type = type,
                total = list.size,
                byStatus = list.groupingBy { it.status }.eachCount(),
                meanScore = mean(list),
                totalProgress = if (type.progressUnit == ProgressUnit.NONE) list.count { it.status == LibraryStatus.COMPLETED }
                else list.sumOf { it.progress }
            )
        }.sortedByDescending { it.total }
        return LibraryStats(
            total = entries.size,
            byStatus = entries.groupingBy { it.status }.eachCount(),
            meanScore = mean(entries),
            byType = byType
        )
    }
}
