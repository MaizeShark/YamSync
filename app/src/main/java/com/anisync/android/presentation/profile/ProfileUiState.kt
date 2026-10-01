package com.anisync.android.presentation.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.anisync.android.R
import com.anisync.android.domain.ActivityHistoryDay
import com.anisync.android.domain.CountryStat
import com.anisync.android.domain.FormatStat
import com.anisync.android.domain.GenreStat
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.StaffStat
import com.anisync.android.domain.StudioStat
import com.anisync.android.domain.TagStat
import com.anisync.android.domain.UserProfile
import com.anisync.android.domain.VoiceActorStat

@Stable
data class ProfileUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val profile: UserProfile? = null,
    val errorMessage: String? = null,
    /** Localized load failure, used where the message is ours rather than the API's. */
    @StringRes val loadErrorRes: Int? = null,
    val selectedTab: ProfileTab = ProfileTab.OVERVIEW,
    val selectedAnimeStatus: LibraryStatus = LibraryStatus.CURRENT,
    val selectedMangaStatus: LibraryStatus = LibraryStatus.CURRENT,
    val selectedStatsType: ProfileStatsType = ProfileStatsType.ANIME,
    val userAnimeList: List<LibraryEntry> = emptyList(),
    val userAnimeListByStatus: Map<LibraryStatus, List<LibraryEntry>> = emptyMap(),
    val isUserAnimeListLoading: Boolean = false,
    val userMangaList: List<LibraryEntry> = emptyList(),
    val userMangaListByStatus: Map<LibraryStatus, List<LibraryEntry>> = emptyMap(),
    val isUserMangaListLoading: Boolean = false,
    val statsData: StatisticsUiModel? = null,
    val isStatsLoading: Boolean = false,
    val statsErrorMessage: String? = null
)

data class StatisticsUiModel(
    val animeStats: AnimeStatisticsUi,
    val mangaStats: MangaStatisticsUi?,
    val activityHistory: List<ActivityHistoryDay> = emptyList()
)

data class AnimeStatisticsUi(
    val totalCount: Int,
    val daysWatched: Double,
    val meanScore: Double,
    val standardDeviation: Double,
    val episodesWatched: Int,
    val minutesWatched: Int,
    val statusDistribution: List<StatusUiModel>,
    val scoreDistribution: List<ScoreUiModel>,
    val genreDistribution: List<GenreStat>,
    val tagDistribution: List<TagStat>,
    val formatDistribution: List<FormatStat>,
    val releaseYearDistribution: List<YearUiModel>,
    val startYearDistribution: List<YearUiModel>,
    val lengthDistribution: List<LengthUiModel>,
    val studioDistribution: List<StudioStat>,
    val voiceActorDistribution: List<VoiceActorStat>,
    val staffDistribution: List<StaffStat>,
    val countryDistribution: List<CountryStat>
)

data class MangaStatisticsUi(
    val totalCount: Int,
    val chaptersRead: Int,
    val volumesRead: Int,
    val meanScore: Double,
    val standardDeviation: Double,
    val statusDistribution: List<StatusUiModel>,
    val scoreDistribution: List<ScoreUiModel>,
    val genreDistribution: List<GenreStat>,
    val tagDistribution: List<TagStat>,
    val formatDistribution: List<FormatStat>,
    val releaseYearDistribution: List<YearUiModel>,
    val startYearDistribution: List<YearUiModel>,
    val lengthDistribution: List<LengthUiModel>,
    val staffDistribution: List<StaffStat>,
    val countryDistribution: List<CountryStat>
)

data class ScoreUiModel(
    val score: Int,
    val label: String,
    val normalizedScore: Float,
    val count: Int,
    val heightFraction: Float
)

data class YearUiModel(
    val year: Int,
    val count: Int,
    val heightFraction: Float
)

data class StatusUiModel(
    val status: String,
    val count: Int,
    val fraction: Float,
    /** 0..4 ordinal mapped to color roles (primary/secondary/tertiary/error/outline). */
    val colorRoleIndex: Int
)

data class LengthUiModel(
    val label: String,
    val count: Int,
    val heightFraction: Float
)

@Immutable
enum class ProfileTab(@StringRes val titleRes: Int) {
    OVERVIEW(R.string.profile_tab_overview),
    ANIME(R.string.media_type_anime),
    MANGA(R.string.media_type_manga),
    STATS(R.string.statistics_title)
}

@Immutable
enum class ProfileStatsType(@StringRes val labelRes: Int) {
    ANIME(R.string.statistics_anime),
    MANGA(R.string.statistics_manga)
}

sealed interface ProfileAction {
    /**
     * [forceNetwork] = true is the user-pull behavior (hit network unconditionally).
     * [forceNetwork] = false serves the cache when available and is used by the screen's
     * auto-refresh on entry so cold opens render instantly.
     */
    data class Refresh(val forceNetwork: Boolean = true) : ProfileAction
    data class SelectTab(val tab: ProfileTab) : ProfileAction
    data class SelectAnimeStatus(val status: LibraryStatus) : ProfileAction
    data class SelectMangaStatus(val status: LibraryStatus) : ProfileAction
    data class SelectStatsType(val type: ProfileStatsType) : ProfileAction
}
