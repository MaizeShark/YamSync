package com.anisync.android.presentation.profile

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anisync.android.R
import com.anisync.android.data.AppSettings
import com.anisync.android.domain.AnimeStatistics
import com.anisync.android.domain.CachePolicy
import com.anisync.android.domain.GetProfileUseCase
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MainTab
import com.anisync.android.domain.MangaStatistics
import com.anisync.android.domain.ProfileRepository
import com.anisync.android.domain.Result
import com.anisync.android.domain.ScoreFormat
import com.anisync.android.domain.StatisticsRepository
import com.anisync.android.domain.TabReselectBus
import com.anisync.android.domain.observeTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

private val LIBRARY_STATUS_DISPLAY_ORDER = arrayOf(
    LibraryStatus.CURRENT,
    LibraryStatus.REPEATING,
    LibraryStatus.PAUSED,
    LibraryStatus.COMPLETED,
    LibraryStatus.PLANNING,
    LibraryStatus.DROPPED
)

/** The signed-in user's own profile: header, library lists per type and statistics. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    getProfileUseCase: GetProfileUseCase,
    private val profileRepository: ProfileRepository,
    private val statisticsRepository: StatisticsRepository,
    private val accountManager: com.anisync.android.data.account.AccountManager,
    appSettings: AppSettings,
    tabReselectBus: TabReselectBus
) : ViewModel() {

    companion object {
        private const val LIST_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        /** Minimum gap between non-user-initiated refreshes (init / ON_RESUME). */
        private const val AUTO_REFRESH_COOLDOWN_MS = 15_000L

        /** Minimum gap between user-initiated refreshes (pull-to-refresh). */
        private const val GESTURE_REFRESH_COOLDOWN_MS = 5_000L
    }

    /** Per-resource cooldown, so repeated pulls and resumes don't stack network refreshes. */
    private class FetchCooldown {
        private var lastAt: Long = 0L
        fun shouldFetch(userInitiated: Boolean): Boolean {
            val floor = if (userInitiated) GESTURE_REFRESH_COOLDOWN_MS else AUTO_REFRESH_COOLDOWN_MS
            val now = SystemClock.elapsedRealtime()
            return (now - lastAt >= floor).also { ok -> if (ok) lastAt = now }
        }
    }

    /** Serializes refresh() invocations so a double-pull only fans out once. */
    private val refreshMutex = Mutex()

    private val profileCooldown = FetchCooldown()

    val titleLanguage: StateFlow<com.anisync.android.data.TitleLanguage> = appSettings.titleLanguage

    // Multi-account quick-switch (own-profile header).
    val accounts: StateFlow<List<com.anisync.android.data.account.Account>> = accountManager.accounts
    val activeAccountId: StateFlow<Int?> = accountManager.activeAccount
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, accountManager.activeAccount.value?.id)

    /**
     * Switches the active account. The keyed MainScreen subtree rebuilds on the resulting session
     * epoch bump, so no explicit screen reload is needed here.
     */
    fun switchAccount(id: Int) {
        accountManager.switch(id)
    }

    private data class ProfileUiLocalState(
        val selectedTab: ProfileTab = ProfileTab.OVERVIEW,
        val selectedAnimeStatus: LibraryStatus = LibraryStatus.CURRENT,
        val selectedMangaStatus: LibraryStatus = LibraryStatus.CURRENT,
        val selectedStatsType: ProfileStatsType = ProfileStatsType.ANIME,
        val isRefreshing: Boolean = false
    )

    private val localState = MutableStateFlow(ProfileUiLocalState())

    private data class StatsState(
        val isStatsLoading: Boolean = false,
        val statsData: StatisticsUiModel? = null,
        val statsErrorMessage: String? = null,
        val hasFetchedStats: Boolean = false
    )

    private val statsState = MutableStateFlow(StatsState())

    private data class MediaListState(
        val userAnimeList: List<LibraryEntry> = emptyList(),
        val userAnimeListByStatus: Map<LibraryStatus, List<LibraryEntry>> = emptyMap(),
        val isUserAnimeListLoading: Boolean = false,
        val hasFetchedAnimeList: Boolean = false,
        val lastAnimeListFetchAtMs: Long = 0L,
        val userMangaList: List<LibraryEntry> = emptyList(),
        val userMangaListByStatus: Map<LibraryStatus, List<LibraryEntry>> = emptyMap(),
        val isUserMangaListLoading: Boolean = false,
        val hasFetchedMangaList: Boolean = false,
        val lastMangaListFetchAtMs: Long = 0L
    )

    private val mediaListState = MutableStateFlow(MediaListState())

    /**
     * Own-profile load failure, set only when Room still holds nothing for the active account.
     * The observed profile flow can only emit null in that state, so without this the screen would
     * sit on a spinner with no way out (#115).
     */
    private val ownProfileError = MutableStateFlow<Int?>(null)

    /** Whether Room currently holds a profile for the active account. Read by [loadOwnProfile]. */
    @Volatile
    private var hasCachedOwnProfile = false

    private val profileState = combine(getProfileUseCase(), ownProfileError) { profileResult, errorRes ->
        when {
            profileResult != null -> ProfileUiState(isLoading = false, profile = profileResult)
            errorRes != null -> ProfileUiState(isLoading = false, loadErrorRes = errorRes)
            else -> ProfileUiState(isLoading = true)
        }
    }
        // Keep the cached account name/avatar (account switcher) in sync with the loaded profile.
        .onEach { state ->
            hasCachedOwnProfile = state.profile != null
            state.profile?.let { accountManager.updateActiveDetails(it.name, it.avatarUrl) }
        }
        .onStart { emit(ProfileUiState(isLoading = true)) }
        .catch { e -> emit(ProfileUiState(isLoading = false, errorMessage = e.message ?: "Unknown error")) }

    private val _scrollToTopRequest = MutableStateFlow(0L)

    /** Bumped when the Profile tab is reselected, asking the page to scroll back to the top. */
    val scrollToTopRequest: StateFlow<Long> = _scrollToTopRequest.asStateFlow()

    val uiState: StateFlow<ProfileUiState> = combine(
        profileState,
        localState,
        statsState,
        mediaListState
    ) { remote, local, stats, mediaLists ->
        remote.copy(
            isRefreshing = local.isRefreshing,
            selectedTab = local.selectedTab,
            selectedAnimeStatus = local.selectedAnimeStatus,
            selectedMangaStatus = local.selectedMangaStatus,
            selectedStatsType = local.selectedStatsType,
            statsData = stats.statsData,
            isStatsLoading = stats.isStatsLoading,
            statsErrorMessage = stats.statsErrorMessage,
            userAnimeList = mediaLists.userAnimeList,
            userAnimeListByStatus = mediaLists.userAnimeListByStatus,
            isUserAnimeListLoading = mediaLists.isUserAnimeListLoading,
            userMangaList = mediaLists.userMangaList,
            userMangaListByStatus = mediaLists.userMangaListByStatus,
            isUserMangaListLoading = mediaLists.isUserMangaListLoading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = ProfileUiState(isLoading = true)
    )

    init {
        tabReselectBus.observeTab(
            tab = MainTab.PROFILE,
            scope = viewModelScope,
            onScrollToTop = { _scrollToTopRequest.update { current -> current + 1 } }
        )

        // Stale-while-revalidate: the cached profile paints instantly, then this deferred pass
        // forces the network. The cooldown keeps quick back-and-forth from re-firing it.
        viewModelScope.launch {
            delay(500)
            if (profileCooldown.shouldFetch(userInitiated = false)) {
                refresh(forceNetwork = true)
            }
        }
    }

    fun onAction(action: ProfileAction) {
        when (action) {
            is ProfileAction.Refresh -> refresh(forceNetwork = action.forceNetwork)
            is ProfileAction.SelectTab -> {
                localState.update { it.copy(selectedTab = action.tab) }
                when {
                    (action.tab == ProfileTab.STATS || action.tab == ProfileTab.OVERVIEW) &&
                        !statsState.value.hasFetchedStats -> fetchStats()
                    action.tab == ProfileTab.ANIME && shouldRefreshAnimeList() &&
                        !mediaListState.value.isUserAnimeListLoading -> fetchUserAnimeList(forceRefresh = true)
                    action.tab == ProfileTab.MANGA && shouldRefreshMangaList() &&
                        !mediaListState.value.isUserMangaListLoading -> fetchUserMangaList(forceRefresh = true)
                }
            }
            is ProfileAction.SelectStatsType -> localState.update { it.copy(selectedStatsType = action.type) }
            is ProfileAction.SelectAnimeStatus -> localState.update { it.copy(selectedAnimeStatus = action.status) }
            is ProfileAction.SelectMangaStatus -> localState.update { it.copy(selectedMangaStatus = action.status) }
        }
    }

    private fun refresh(forceNetwork: Boolean = true) {
        // Drop a second pull while the first is still in flight instead of stacking it.
        if (!refreshMutex.tryLock()) return
        localState.update { it.copy(isRefreshing = true) }

        viewModelScope.launch {
            try {
                val profileJob = launch { loadOwnProfile(forceNetwork) }
                val activeTabJob = launch {
                    when (localState.value.selectedTab) {
                        ProfileTab.STATS -> fetchStats(forceRefresh = true)
                        ProfileTab.OVERVIEW -> fetchStats(forceRefresh = forceNetwork)
                        ProfileTab.ANIME -> fetchUserAnimeList(forceRefresh = shouldRefreshAnimeList())
                        ProfileTab.MANGA -> fetchUserMangaList(forceRefresh = shouldRefreshMangaList())
                    }
                }
                profileJob.join()
                activeTabJob.join()
            } finally {
                localState.update { it.copy(isRefreshing = false) }
                refreshMutex.unlock()
            }
        }
    }

    /**
     * Fetches the own profile, surfacing a failure while Room holds nothing for the active account.
     * A failure with a profile already on screen stays silent.
     */
    private suspend fun loadOwnProfile(forceNetwork: Boolean) {
        ownProfileError.value = null
        val result = profileRepository.refreshProfileTimed("", forceNetwork = forceNetwork)
        if (result is Result.Error) {
            Log.w("AniSyncPerf", "profile.load failed code=${result.code} msg=${result.message}")
            if (!hasCachedOwnProfile && result.code != 429) {
                ownProfileError.value = R.string.profile_unknown_error
            }
        }
    }

    private fun fetchStats(forceRefresh: Boolean = false) {
        if (statsState.value.isStatsLoading) return
        if (!forceRefresh && statsState.value.hasFetchedStats) return

        viewModelScope.launch {
            statsState.update { it.copy(isStatsLoading = true, statsErrorMessage = null) }
            // The Overview tab's cold-open auto-refresh fires this before the profile is
            // necessarily in uiState (the stats fetch races the profile load). Bailing on a
            // null id left the activity-history heatmap empty until a manual pull-to-refresh;
            // instead, await the first non-null id so it populates on its own.
            val userId = uiState.value.profile?.id
                ?: uiState.map { it.profile?.id }.filterNotNull().first()

            // Force the network on an explicit refresh; otherwise network-first with a
            // cache fallback (matches the other profile tabs). A cache-first default here
            // froze the activity-history heatmap at first fetch — the normalized cache
            // never revalidates on its own, so new activity never showed (#88).
            val policy = if (forceRefresh) CachePolicy.NetworkOnly else CachePolicy.NetworkFirst
            when (val result = statisticsRepository.getUserStatistics(userId, policy)) {
                is Result.Success -> {
                    // Process data on default dispatcher
                    val processedData = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        val animeUi = processAnimeStats(result.data.scoreFormat, result.data.animeStats)
                        val mangaUi = result.data.mangaStats?.let { processMangaStats(it, result.data.scoreFormat) }
                        StatisticsUiModel(animeUi, mangaUi, activityHistory = result.data.activityHistory)
                    }

                    statsState.update {
                        it.copy(
                            isStatsLoading = false,
                            statsData = processedData,
                            hasFetchedStats = true
                        )
                    }
                }
                is Result.Error -> {
                    statsState.update {
                        it.copy(
                            isStatsLoading = false,
                            statsErrorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    private fun processAnimeStats(scoreFormat: ScoreFormat?, stats: AnimeStatistics): AnimeStatisticsUi {
        val effectiveScoreFormat = scoreFormat ?: inferScoreFormat(stats.scoreDistribution.map { it.score })
        val scoreUi = bucketScores(effectiveScoreFormat, stats.scoreDistribution.map { it.score to it.count })

        val sortedReleaseYears = stats.releaseYearDistribution.sortedBy { it.year }.takeLast(12)
        val maxReleaseYearCount = sortedReleaseYears.maxOfOrNull { it.count } ?: 1
        val releaseYearsUi = sortedReleaseYears.map {
            YearUiModel(it.year, it.count, it.count.toFloat() / maxReleaseYearCount.coerceAtLeast(1))
        }

        val sortedStartYears = stats.startYearDistribution.sortedBy { it.year }.takeLast(12)
        val maxStartYearCount = sortedStartYears.maxOfOrNull { it.count } ?: 1
        val startYearsUi = sortedStartYears.map {
            YearUiModel(it.year, it.count, it.count.toFloat() / maxStartYearCount.coerceAtLeast(1))
        }

        val maxLengthCount = stats.lengthDistribution.maxOfOrNull { it.count } ?: 1
        val lengthsUi = stats.lengthDistribution.map {
            LengthUiModel(it.length, it.count, it.count.toFloat() / maxLengthCount.coerceAtLeast(1))
        }

        val statusesUi = stats.statusDistribution.toStatusUi()

        return AnimeStatisticsUi(
            totalCount = stats.totalCount,
            daysWatched = stats.daysWatched.toDouble(),
            meanScore = stats.meanScore.toDouble(),
            standardDeviation = stats.standardDeviation.toDouble(),
            episodesWatched = stats.episodesWatched,
            minutesWatched = stats.minutesWatched,
            statusDistribution = statusesUi,
            scoreDistribution = scoreUi,
            genreDistribution = stats.genreDistribution.take(20),
            tagDistribution = stats.tagDistribution.take(25),
            formatDistribution = stats.formatDistribution,
            releaseYearDistribution = releaseYearsUi,
            startYearDistribution = startYearsUi,
            lengthDistribution = lengthsUi,
            studioDistribution = stats.studioDistribution.take(20),
            voiceActorDistribution = stats.voiceActorDistribution.take(10),
            staffDistribution = stats.staffDistribution.take(10),
            countryDistribution = stats.countryDistribution
        )
    }

    private fun processMangaStats(stats: MangaStatistics, scoreFormat: ScoreFormat?): MangaStatisticsUi {
        val effectiveScoreFormat = scoreFormat ?: inferScoreFormat(stats.scoreDistribution.map { it.score })
        val scoreUi = bucketScores(effectiveScoreFormat, stats.scoreDistribution.map { it.score to it.count })

        val sortedReleaseYears = stats.releaseYearDistribution.sortedBy { it.year }.takeLast(12)
        val maxReleaseYearCount = sortedReleaseYears.maxOfOrNull { it.count } ?: 1
        val releaseYearsUi = sortedReleaseYears.map {
            YearUiModel(it.year, it.count, it.count.toFloat() / maxReleaseYearCount.coerceAtLeast(1))
        }

        val sortedStartYears = stats.startYearDistribution.sortedBy { it.year }.takeLast(12)
        val maxStartYearCount = sortedStartYears.maxOfOrNull { it.count } ?: 1
        val startYearsUi = sortedStartYears.map {
            YearUiModel(it.year, it.count, it.count.toFloat() / maxStartYearCount.coerceAtLeast(1))
        }

        val maxLengthCount = stats.lengthDistribution.maxOfOrNull { it.count } ?: 1
        val lengthsUi = stats.lengthDistribution.map {
            LengthUiModel(it.length, it.count, it.count.toFloat() / maxLengthCount.coerceAtLeast(1))
        }

        val statusesUi = stats.statusDistribution.toStatusUi()

        return MangaStatisticsUi(
            totalCount = stats.totalCount,
            chaptersRead = stats.chaptersRead,
            volumesRead = stats.volumesRead,
            meanScore = stats.meanScore.toDouble(),
            standardDeviation = stats.standardDeviation.toDouble(),
            statusDistribution = statusesUi,
            scoreDistribution = scoreUi,
            genreDistribution = stats.genreDistribution.take(20),
            tagDistribution = stats.tagDistribution.take(25),
            formatDistribution = stats.formatDistribution,
            releaseYearDistribution = releaseYearsUi,
            startYearDistribution = startYearsUi,
            lengthDistribution = lengthsUi,
            staffDistribution = stats.staffDistribution.take(10),
            countryDistribution = stats.countryDistribution
        )
    }

    private fun bucketScores(
        format: ScoreFormat,
        scores: List<Pair<Int, Int>>
    ): List<ScoreUiModel> {
        val bucketCount = when (format) {
            ScoreFormat.POINT_100, ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_10 -> 10
            ScoreFormat.POINT_5 -> 5
            ScoreFormat.POINT_3 -> 3
        }
        val maxRawScore = when (format) {
            ScoreFormat.POINT_100, ScoreFormat.POINT_10_DECIMAL -> 100
            ScoreFormat.POINT_10 -> 10
            ScoreFormat.POINT_5 -> 5
            ScoreFormat.POINT_3 -> 3
        }
        val counts = IntArray(bucketCount)
        scores.forEach { (rawScore, count) ->
            val s = rawScore.coerceIn(1, maxRawScore)
            val bucketIndex = when (format) {
                ScoreFormat.POINT_100, ScoreFormat.POINT_10_DECIMAL -> ((s - 1) / 10).coerceIn(0, bucketCount - 1)
                else -> (s - 1).coerceIn(0, bucketCount - 1)
            }
            counts[bucketIndex] += count
        }
        val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        return (1..bucketCount).map { bucket ->
            val count = counts[bucket - 1]
            val label = when (format) {
                ScoreFormat.POINT_100 -> (bucket * 10).toString()
                ScoreFormat.POINT_10_DECIMAL -> "$bucket.0"
                else -> bucket.toString()
            }
            val normalized = when (format) {
                ScoreFormat.POINT_100 -> (bucket * 10) / 100f
                ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_10 -> bucket / 10f
                ScoreFormat.POINT_5 -> bucket / 5f
                ScoreFormat.POINT_3 -> bucket / 3f
            }
            ScoreUiModel(
                score = bucket,
                label = label,
                normalizedScore = normalized,
                count = count,
                heightFraction = count.toFloat() / maxCount
            )
        }
    }

    private fun List<com.anisync.android.domain.StatusStat>.toStatusUi(): List<StatusUiModel> {
        val total = sumOf { it.count }.coerceAtLeast(1)
        // Stable color role per status for theme-driven palette
        val statusOrder = listOf("CURRENT", "COMPLETED", "PLANNING", "PAUSED", "DROPPED", "REPEATING")
        return sortedBy { statusOrder.indexOf(it.status).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
            .mapIndexed { index, stat ->
                StatusUiModel(
                    status = stat.status,
                    count = stat.count,
                    fraction = stat.count.toFloat() / total,
                    colorRoleIndex = index % 5
                )
            }
    }

    private fun inferScoreFormat(scores: List<Int>): ScoreFormat {
        val maxScore = scores.maxOrNull() ?: 10
        return when {
            maxScore > 10 -> ScoreFormat.POINT_100
            maxScore > 5 -> ScoreFormat.POINT_10
            maxScore > 3 -> ScoreFormat.POINT_5
            else -> ScoreFormat.POINT_3
        }
    }

    private fun fetchUserAnimeList(forceRefresh: Boolean = false) {
        if (mediaListState.value.isUserAnimeListLoading) return
        if (!forceRefresh && mediaListState.value.hasFetchedAnimeList) return

        viewModelScope.launch {
            mediaListState.update { it.copy(isUserAnimeListLoading = true) }
            val username = uiState.value.profile?.name
            if (username.isNullOrBlank()) {
                mediaListState.update { it.copy(isUserAnimeListLoading = false) }
                return@launch
            }

            when (val result = profileRepository.getUserAnimeList(username)) {
                is Result.Success -> {
                    val grouped = groupEntriesByStatus(result.data)
                    mediaListState.update {
                        it.copy(
                            isUserAnimeListLoading = false,
                            userAnimeList = result.data,
                            userAnimeListByStatus = grouped,
                            hasFetchedAnimeList = true,
                            lastAnimeListFetchAtMs = System.currentTimeMillis()
                        )
                    }
                }
                is Result.Error -> {
                    mediaListState.update { it.copy(isUserAnimeListLoading = false) }
                }
            }
        }
    }

    private fun fetchUserMangaList(forceRefresh: Boolean = false) {
        if (mediaListState.value.isUserMangaListLoading) return
        if (!forceRefresh && mediaListState.value.hasFetchedMangaList) return

        viewModelScope.launch {
            mediaListState.update { it.copy(isUserMangaListLoading = true) }
            val username = uiState.value.profile?.name
            if (username.isNullOrBlank()) {
                mediaListState.update { it.copy(isUserMangaListLoading = false) }
                return@launch
            }

            when (val result = profileRepository.getUserMangaList(username)) {
                is Result.Success -> {
                    val grouped = groupEntriesByStatus(result.data)
                    mediaListState.update {
                        it.copy(
                            isUserMangaListLoading = false,
                            userMangaList = result.data,
                            userMangaListByStatus = grouped,
                            hasFetchedMangaList = true,
                            lastMangaListFetchAtMs = System.currentTimeMillis()
                        )
                    }
                }
                is Result.Error -> {
                    mediaListState.update { it.copy(isUserMangaListLoading = false) }
                }
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            accountManager.logoutActive()
            onComplete()
        }
    }

    private fun groupEntriesByStatus(entries: List<LibraryEntry>): Map<LibraryStatus, List<LibraryEntry>> {
        val grouped = entries.groupBy { it.status }
        val ordered = LinkedHashMap<LibraryStatus, List<LibraryEntry>>(LIBRARY_STATUS_DISPLAY_ORDER.size + 1)
        for (status in LIBRARY_STATUS_DISPLAY_ORDER) {
            ordered[status] = grouped[status].orEmpty()
        }
        val unknownItems = grouped[LibraryStatus.UNKNOWN].orEmpty()
        if (unknownItems.isNotEmpty()) {
            ordered[LibraryStatus.UNKNOWN] = unknownItems
        }
        return ordered
    }

    private fun shouldRefreshAnimeList(): Boolean {
        val state = mediaListState.value
        if (!state.hasFetchedAnimeList) return true
        return System.currentTimeMillis() - state.lastAnimeListFetchAtMs >= LIST_REFRESH_INTERVAL_MS
    }

    private fun shouldRefreshMangaList(): Boolean {
        val state = mediaListState.value
        if (!state.hasFetchedMangaList) return true
        return System.currentTimeMillis() - state.lastMangaListFetchAtMs >= LIST_REFRESH_INTERVAL_MS
    }
}
