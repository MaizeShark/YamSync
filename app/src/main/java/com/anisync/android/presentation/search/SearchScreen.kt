package com.anisync.android.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anisync.android.R
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaCard
import com.anisync.android.domain.model.MediaType
import com.anisync.android.presentation.components.AppCircularProgressIndicator
import com.anisync.android.presentation.util.bouncyClickable
import com.anisync.android.presentation.util.pluralLabel
import com.anisync.android.presentation.util.toColor
import com.anisync.android.presentation.util.toLabel

/** Searches every type Yamtrack tracks, each through its own metadata provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onMediaClick: (Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_type_hint, state.type.pluralLabel())) },
                        singleLine = true,
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            viewModel.submit()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(MediaType.entries.filter { it.isTopLevel }, key = { it.slug }) { type ->
                    FilterChip(
                        selected = type == state.type,
                        onClick = { viewModel.onTypeChange(type) },
                        label = { Text(type.pluralLabel()) }
                    )
                }
            }
            if (state.type.sources.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.type.sources, key = { it }) { source ->
                        FilterChip(
                            selected = (state.source ?: state.type.defaultSource) == source,
                            onClick = { viewModel.onSourceChange(source) },
                            label = { Text(sourceLabel(source)) }
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.results.isEmpty() && state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AppCircularProgressIndicator()
                    }
                    state.results.isEmpty() && state.errorMessage != null -> CenteredText(state.errorMessage.orEmpty())
                    state.results.isEmpty() && state.hasSearched -> CenteredText(stringResource(R.string.search_no_results))
                    state.results.isEmpty() -> CenteredText(stringResource(R.string.search_start_hint))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.results, key = { it.key.asString() }) { card ->
                            ResultCard(
                                card = card,
                                status = statuses[card.mediaId],
                                isAdding = card.mediaId in state.adding,
                                onClick = { onMediaClick(card.mediaId) },
                                onAdd = { viewModel.addToPlanning(card) }
                            )
                        }
                        if (state.hasNextPage) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                                LaunchedEffect(state.results.size) { viewModel.loadMore() }
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    AppCircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    card: MediaCard,
    status: LibraryStatus?,
    isAdding: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit
) {
    Column(Modifier.bouncyClickable(clipShape = RoundedCornerShape(14.dp), onClick = onClick)) {
        Box {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (status != null) {
                Surface(
                    shape = CircleShape,
                    color = status.toColor(),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        status.toLabel(card.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            } else {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                    if (isAdding) {
                        AppCircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        SmallFloatingActionButton(onClick = onAdd) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.details_quick_plan))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Yamtrack's names for its metadata sources. */
private fun sourceLabel(source: String): String = when (source) {
    "mal" -> "MyAnimeList"
    "mangaupdates" -> "MangaUpdates"
    "tmdb" -> "TMDB"
    "igdb" -> "IGDB"
    "hardcover" -> "Hardcover"
    "openlibrary" -> "Open Library"
    "comicvine" -> "Comic Vine"
    "bgg" -> "BoardGameGeek"
    else -> source
}
