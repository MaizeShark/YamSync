package com.anisync.android.presentation.profile.sections

import com.anisync.android.ui.theme.emphasis
import com.anisync.android.domain.url

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anisync.android.R
import com.anisync.android.domain.ActivityHistoryDay
import com.anisync.android.domain.UserProfile
import com.anisync.android.presentation.components.HeaderLevel
import com.anisync.android.presentation.components.PosterCard
import com.anisync.android.presentation.components.SectionHeader
import com.anisync.android.presentation.details.components.CharacterItem
import com.anisync.android.presentation.profile.ProfileTab
import com.anisync.android.presentation.profile.components.PlaceholderTabContent
import com.anisync.android.presentation.statistics.ActivityHistorySection
import com.anisync.android.presentation.statistics.GenreCardModern
import com.anisync.android.presentation.util.bouncyClickable

@Composable
fun ProfileOverviewSection(
    profile: UserProfile,
    activityHistory: List<ActivityHistoryDay> = emptyList(),
    onNavigateToTab: (ProfileTab) -> Unit = {},
    onMediaClick: (Int) -> Unit = {},
    onActivityClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasLibrary = profile.animeCount > 0 || profile.mangaCount > 0

    if (!hasLibrary && activityHistory.isEmpty()) {
        PlaceholderTabContent(
            message = stringResource(R.string.profile_no_recent_updates),
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(16.dp))

        // Activity heatmap — leads the tab as the most glanceable "what they've been up to".
        if (activityHistory.isNotEmpty()) {
            ActivityHistorySection(
                activityHistory,
                userId = profile.id,
                onMediaClick = onMediaClick,
                onActivityClick = onActivityClick
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Library snapshot — the at-a-glance summary; the header's expand button opens full Stats.
        if (hasLibrary) {
            SectionHeader(
                title = stringResource(R.string.statistics_title),
                level = HeaderLevel.Section,
                padding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                onActionClick = { onNavigateToTab(ProfileTab.STATS) }
            )
            ProfileLibrarySnapshot(profile = profile)

            if (profile.topGenres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(
                    title = stringResource(R.string.statistics_top_genres),
                    level = HeaderLevel.Section,
                    padding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    onActionClick = { onNavigateToTab(ProfileTab.STATS) }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(profile.topGenres.take(5), key = { it.genre }) { genre ->
                        GenreCardModern(genre = genre)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
