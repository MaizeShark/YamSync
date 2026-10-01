package com.anisync.android.presentation.details

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anisync.android.R
import com.anisync.android.domain.EpisodeInfo
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.MediaCard
import com.anisync.android.domain.MediaDetails
import com.anisync.android.domain.MediaTheme
import com.anisync.android.domain.model.MediaType
import com.anisync.android.presentation.components.AppCircularProgressIndicator
import com.anisync.android.presentation.components.ErrorState
import com.anisync.android.presentation.components.HeaderLevel
import com.anisync.android.presentation.components.SectionHeader
import com.anisync.android.presentation.details.components.ExpandableSynopsis
import com.anisync.android.presentation.details.components.MediaThemesSection
import com.anisync.android.presentation.details.components.NextEpisodeStrip
import com.anisync.android.presentation.details.components.ThemeSheet
import com.anisync.android.presentation.details.components.TrackingCard
import com.anisync.android.presentation.details.components.UserNotesCard
import com.anisync.android.presentation.library.components.EditLibraryEntrySheet
import com.anisync.android.presentation.util.LocalPaneIsRoot
import com.anisync.android.presentation.util.bouncyClickable
import com.anisync.android.presentation.util.label
import com.anisync.android.util.AppLinksUtil
import com.anisync.android.util.ShareUtils

/**
 * One item's page: the tracker first, then what the metadata provider says about it, then the
 * things it leads to (seasons, episodes, related titles).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MediaDetailsScreen(
    mediaId: Int,
    sourceScreen: String = "unknown",
    onBackClick: () -> Unit,
    onMediaClick: (Int) -> Unit = {},
    onThemesSeeAllClick: (mediaId: Int, mediaTitle: String, totalEpisodes: Int?, coverUrl: String?) -> Unit = { _, _, _, _ -> },
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    viewModel: MediaDetailsViewModel = hiltViewModel(),
    themesViewModel: MediaThemesViewModel = hiltViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val themes by themesViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openTheme by remember { mutableStateOf<MediaTheme?>(null) }

    val details = state.details
    val type = details?.type ?: state.summary?.key?.type
    LaunchedEffect(type) { if (type != null) themesViewModel.start(type == MediaType.ANIME) }

    val title = details?.let { d -> listOfNotNull(d.title, d.subtitle).joinToString(" · ") }
        ?: state.summary?.title.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!LocalPaneIsRoot.current) {
                        IconButton(onClick = onBackClick) {
                            Icon(navigationIcon, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    details?.sourceUrl?.let { url ->
                        IconButton(onClick = { AppLinksUtil.openInBrowser(context, url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.settings_open_in_web))
                        }
                        IconButton(onClick = { ShareUtils.shareText(context, "${details.title}\n$url") }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                    }
                    if (LocalPaneIsRoot.current) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                details == null && state.isLoading && state.summary == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { AppCircularProgressIndicator() }

                details == null && state.errorMessage != null && state.summary == null -> ErrorState(
                    message = state.errorMessage.orEmpty(),
                    onRetry = viewModel::refresh
                )

                else -> DetailsContent(
                    details = details,
                    fallbackTitle = state.summary?.title.orEmpty(),
                    fallbackImage = state.summary?.imageUrl,
                    type = type ?: MediaType.ANIME,
                    isLoading = details == null && state.isLoading,
                    errorMessage = state.errorMessage.takeIf { details == null },
                    entry = entry,
                    maxProgress = details?.maxProgress ?: state.summary?.maxProgress,
                    markingEpisodes = state.markingEpisodes,
                    themesContent = {
                        if (type == MediaType.ANIME) {
                            MediaThemesSection(
                                themes = themes.themes,
                                isLoading = themes.isLoading || !themes.hasLoaded,
                                errorMessage = themes.errorMessage,
                                retryAfterSeconds = themes.retryAfterSeconds,
                                coverUrl = details?.imageUrl,
                                totalEpisodes = details?.maxProgress,
                                onSeeAllClick = {
                                    onThemesSeeAllClick(mediaId, details?.title ?: title, details?.maxProgress, details?.imageUrl)
                                },
                                onThemeClick = { openTheme = it },
                                onRetryClick = themesViewModel::retry
                            )
                        }
                    },
                    onStatusSelect = viewModel::setStatus,
                    onProgressChange = viewModel::setProgress,
                    onEditClick = viewModel::openEditor,
                    onRemoveClick = viewModel::delete,
                    onMediaClick = onMediaClick,
                    onEpisodeWatched = viewModel::markEpisodeWatched,
                    onRetry = viewModel::refresh
                )
            }
        }
    }

    editing?.let { current ->
        EditLibraryEntrySheet(
            entry = current,
            maxProgress = details?.maxProgress ?: current.maxProgress,
            isSaving = state.isSaving,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onAddRewatch = viewModel::addRewatch
        )
    }

    openTheme?.let { theme ->
        ThemeSheet(
            theme = theme,
            totalEpisodes = details?.maxProgress,
            animeSlug = themes.animeSlug,
            onDismiss = { openTheme = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsContent(
    details: MediaDetails?,
    fallbackTitle: String,
    fallbackImage: String?,
    type: MediaType,
    isLoading: Boolean,
    errorMessage: String?,
    entry: LibraryEntry?,
    maxProgress: Int?,
    markingEpisodes: Set<Int>,
    themesContent: @Composable () -> Unit,
    onStatusSelect: (com.anisync.android.domain.LibraryStatus) -> Unit,
    onProgressChange: (Int) -> Unit,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onMediaClick: (Int) -> Unit,
    onEpisodeWatched: (Int) -> Unit,
    onRetry: () -> Unit
) {
    val horizontal = dimensionResource(R.dimen.spacing_large)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimensionResource(R.dimen.spacing_extra_large)),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_large))
    ) {
        item(key = "header") {
            Header(
                title = details?.title ?: fallbackTitle,
                subtitle = details?.subtitle,
                imageUrl = details?.imageUrl ?: fallbackImage,
                type = type,
                genres = details?.genres.orEmpty(),
                score = details?.score,
                scoreCount = details?.scoreCount,
                modifier = Modifier.padding(horizontal = horizontal)
            )
        }

        item(key = "tracking") {
            Column(
                modifier = Modifier.padding(horizontal = horizontal),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_normal))
            ) {
                TrackingCard(
                    entry = entry,
                    mediaType = type,
                    maxProgress = maxProgress,
                    onStatusSelect = onStatusSelect,
                    onProgressChange = onProgressChange,
                    onEditClick = onEditClick,
                    onRemoveClick = onRemoveClick
                )
                val notes = entry?.notes
                if (!notes.isNullOrBlank()) {
                    UserNotesCard(notes = notes, onEditClick = onEditClick)
                }
                val next = entry?.nextAiringEpisode
                val nextAt = entry?.nextAiringEpisodeTime
                if (next != null && nextAt != null) {
                    NextEpisodeStrip(episode = next, airingAtEpochSeconds = nextAt)
                }
            }
        }

        if (isLoading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    AppCircularProgressIndicator()
                }
            }
        }
        if (errorMessage != null) {
            item(key = "error") {
                ErrorState(message = errorMessage, onRetry = onRetry, modifier = Modifier.height(320.dp))
            }
        }
        if (details == null) return@LazyColumn

        details.synopsis?.let { synopsis ->
            item(key = "synopsis") {
                Box(Modifier.padding(horizontal = horizontal)) { ExpandableSynopsis(synopsis) }
            }
        }

        if (details.info.isNotEmpty()) {
            item(key = "info") {
                InfoTable(details.info, modifier = Modifier.padding(horizontal = horizontal))
            }
        }

        item(key = "themes") { themesContent() }

        if (details.seasons.isNotEmpty()) {
            item(key = "seasons") {
                CardRow(
                    title = stringResource(R.string.details_seasons),
                    cards = details.seasons,
                    onClick = onMediaClick
                )
            }
        }

        if (details.episodes.isNotEmpty()) {
            item(key = "episodes_header") {
                SectionHeader(title = stringResource(R.string.details_episodes), level = HeaderLevel.Section)
            }
            items(details.episodes, key = { "episode_${it.number}" }) { episode ->
                EpisodeRow(
                    episode = episode,
                    isMarking = episode.number in markingEpisodes,
                    onWatched = { onEpisodeWatched(episode.number) },
                    modifier = Modifier.padding(horizontal = horizontal)
                )
            }
        }

        if (details.streamingProviders.isNotEmpty()) {
            item(key = "streaming") {
                Column(Modifier.padding(horizontal = horizontal)) {
                    SectionHeader(
                        title = stringResource(R.string.subsection_streaming),
                        level = HeaderLevel.Section,
                        padding = PaddingValues(bottom = 8.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.streamingProviders.forEach { provider ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = provider.logoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(provider.name, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        details.related.forEach { group ->
            item(key = "related_${group.title}") {
                CardRow(title = group.title, cards = group.items, onClick = onMediaClick)
            }
        }

        if (details.cast.isNotEmpty()) {
            item(key = "cast") {
                Column {
                    SectionHeader(title = stringResource(R.string.details_cast), level = HeaderLevel.Section)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontal),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(details.cast, key = { it.name + it.role }) { member ->
                            Column(Modifier.width(96.dp)) {
                                AsyncImage(
                                    model = member.imageUrl,
                                    contentDescription = member.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Text(member.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                member.role?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (details.externalLinks.isNotEmpty()) {
            item(key = "links") {
                LinksRow(details.externalLinks, modifier = Modifier.padding(horizontal = horizontal))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    type: MediaType,
    genres: List<String>,
    score: Double?,
    scoreCount: Int?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AsyncImage(
            model = imageUrl,
            contentDescription = stringResource(R.string.content_description_cover),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(type.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (score != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = buildString {
                            append(score)
                            append(" / 10")
                            if (scoreCount != null) append("  ·  ").append(scoreCount)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            if (genres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    genres.take(6).forEach { genre ->
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                genre,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTable(info: List<Pair<String, List<String>>>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_extra_large)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            info.forEach { (label, values) ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = label.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = values.joinToString("\n").ifEmpty { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CardRow(title: String, cards: List<MediaCard>, onClick: (Int) -> Unit) {
    Column {
        SectionHeader(title = title, level = HeaderLevel.Section)
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = dimensionResource(R.dimen.spacing_large)),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cards, key = { it.key.asString() }) { card ->
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .bouncyClickable(clipShape = RoundedCornerShape(12.dp)) { onClick(card.mediaId) }
                ) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(card.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeInfo,
    isMarking: Boolean,
    onWatched: () -> Unit,
    modifier: Modifier = Modifier
) {
    val watched = episode.watchCount > 0
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull("${episode.number}.", episode.title).joinToString(" "),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(episode.airDate, episode.runtime).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (episode.watchCount > 1) {
                    Text(
                        stringResource(R.string.details_watched_times, episode.watchCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (isMarking) {
                AppCircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onWatched) {
                    Icon(
                        imageVector = if (watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.details_mark_watched),
                        tint = if (watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinksRow(links: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        SectionHeader(
            title = stringResource(R.string.details_links),
            level = HeaderLevel.Section,
            padding = PaddingValues(bottom = 8.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (name, url) ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.bouncyClickable(clipShape = CircleShape) { AppLinksUtil.openInBrowser(context, url) }
                ) {
                    Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}
