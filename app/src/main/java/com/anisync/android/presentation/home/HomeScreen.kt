package com.anisync.android.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anisync.android.R
import com.anisync.android.domain.AiringEpisode
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.model.MediaType
import com.anisync.android.presentation.components.AppCircularProgressIndicator
import com.anisync.android.presentation.components.HeaderLevel
import com.anisync.android.presentation.components.ScrollToTopOnRequest
import com.anisync.android.presentation.components.SectionHeader
import com.anisync.android.presentation.util.LocalMainNavBarInset
import com.anisync.android.presentation.util.bouncyClickable
import com.anisync.android.presentation.util.label
import com.anisync.android.presentation.util.progressText
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMediaClick: (Int) -> Unit,
    onSearchClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val inProgress by viewModel.inProgress.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val scrollToTop by viewModel.scrollToTop.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    ScrollToTopOnRequest(scrollToTop, listState)

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp + LocalMainNavBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "top") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(CircleShape)
                            .bouncyClickable(clipShape = CircleShape, onClick = onSearchClick)
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.home_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onCalendarClick) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.calendar_title))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            }

            item(key = "continue_header") {
                SectionHeader(title = stringResource(R.string.home_in_progress), level = HeaderLevel.Section)
            }
            if (inProgress.isEmpty()) {
                item(key = "continue_empty") {
                    Text(
                        text = stringResource(R.string.home_in_progress_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            items(inProgress, key = { "progress_${it.mediaId}" }) { entry ->
                InProgressRow(
                    entry = entry,
                    isSaving = entry.mediaId in saving,
                    onClick = { onMediaClick(entry.mediaId) },
                    onIncrement = { viewModel.increment(entry) },
                    modifier = Modifier.padding(horizontal = 16.dp).animateItem()
                )
            }

            if (upcoming.isNotEmpty()) {
                item(key = "upcoming_header") {
                    SectionHeader(
                        title = stringResource(R.string.home_upcoming),
                        level = HeaderLevel.Section,
                        onActionClick = onCalendarClick
                    )
                }
                items(upcoming.take(15), key = { "upcoming_${it.id}" }) { release ->
                    UpcomingRow(
                        release = release,
                        onClick = { if (release.mediaId > 0) onMediaClick(release.mediaId) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InProgressRow(
    entry: LibraryEntry,
    isSaving: Boolean,
    onClick: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(clipShape = RoundedCornerShape(20.dp), onClick = onClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 52.dp, height = 74.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.type.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progressText(entry.type, entry.progress, entry.maxProgress)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (entry.type.hasEditableProgress) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (isSaving) {
                        AppCircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        FilledTonalIconButton(
                            onClick = onIncrement,
                            enabled = entry.maxProgress == null || entry.type == MediaType.GAME || entry.progress < entry.maxProgress
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.a11y_action_increment_progress))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(release: AiringEpisode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable(clipShape = RoundedCornerShape(16.dp), onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = release.coverImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 40.dp, height = 56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(release.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    release.episode.takeIf { it > 0 }?.let { stringResource(R.string.home_release_number, it) },
                    formatter.format(Date(release.airingAt * 1000))
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
