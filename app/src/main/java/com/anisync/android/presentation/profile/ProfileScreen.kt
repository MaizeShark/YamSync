package com.anisync.android.presentation.profile

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anisync.android.R
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.ProgressUnit
import com.anisync.android.presentation.components.HeaderLevel
import com.anisync.android.presentation.components.ScrollToTopOnRequest
import com.anisync.android.presentation.components.SectionHeader
import com.anisync.android.presentation.components.UserAvatar
import com.anisync.android.presentation.profile.components.AccountSwitcherSheet
import com.anisync.android.presentation.util.LocalMainNavBarInset
import com.anisync.android.presentation.util.formatPlayTime
import com.anisync.android.presentation.util.icon
import com.anisync.android.presentation.util.pluralLabel
import com.anisync.android.presentation.util.toColor
import com.anisync.android.presentation.util.toLabel
import com.anisync.android.presentation.util.unitLabel
import java.util.Locale

/** The signed-in account, its server, and what its library adds up to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val account by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val scrollToTop by viewModel.scrollToTop.collectAsStateWithLifecycle()
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "account") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(url = account?.avatarUrl, contentDescription = null, size = 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(account?.serverLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (accounts.size > 1) {
                        FilledTonalIconButton(onClick = { showSwitcher = true }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.account_switch))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            }

            item(key = "summary") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.profile_entries), stats.total.toString(), Modifier.weight(1f))
                    StatTile(
                        stringResource(R.string.profile_mean_score),
                        stats.meanScore?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–",
                        Modifier.weight(1f)
                    )
                    StatTile(
                        stringResource(R.string.status_completed),
                        (stats.byStatus[LibraryStatus.COMPLETED] ?: 0).toString(),
                        Modifier.weight(1f)
                    )
                }
            }

            if (stats.total > 0) {
                item(key = "status_bar") {
                    StatusBar(stats.byStatus, stats.total, Modifier.padding(horizontal = 16.dp))
                }
            }

            if (stats.byType.isNotEmpty()) {
                item(key = "types_header") {
                    SectionHeader(title = stringResource(R.string.profile_by_type), level = HeaderLevel.Section)
                }
                items(stats.byType, key = { it.type.slug }) { typeStats ->
                    TypeRow(typeStats, Modifier.padding(horizontal = 16.dp))
                }
            }

            item(key = "logout") {
                TextButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.control_log_out), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showSwitcher) {
        AccountSwitcherSheet(
            accounts = accounts,
            activeAccountId = account?.id,
            onSwitch = { id ->
                showSwitcher = false
                viewModel.switchAccount(id)
            },
            onAddAccount = {
                showSwitcher = false
                onAddAccount()
            },
            onDismiss = { showSwitcher = false }
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout_dialog_title)) },
            text = { Text(stringResource(R.string.logout_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.logout_dialog_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The library split by status, as one segmented bar with a legend. */
@Composable
private fun StatusBar(byStatus: Map<LibraryStatus, Int>, total: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
        ) {
            LibraryStatus.entries.forEach { status ->
                val count = byStatus[status] ?: 0
                if (count > 0) {
                    Box(
                        Modifier
                            .weight(count.toFloat() / total)
                            .height(12.dp)
                            .background(status.toColor())
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryStatus.entries.forEach { status ->
                val count = byStatus[status] ?: 0
                if (count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(status.toColor()))
                        Spacer(Modifier.width(4.dp))
                        Text("${status.toLabel(null)} $count", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeRow(stats: TypeStats, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(stats.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stats.type.pluralLabel(), style = MaterialTheme.typography.titleMedium)
                val progress = when (stats.type.progressUnit) {
                    ProgressUnit.MINUTES -> formatPlayTime(stats.totalProgress)
                    ProgressUnit.NONE -> stringResource(R.string.profile_completed_count, stats.totalProgress)
                    else -> "${stats.totalProgress} ${stats.type.unitLabel(stats.totalProgress).orEmpty()}"
                }
                Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stats.total.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                stats.meanScore?.let {
                    Text(
                        String.format(Locale.getDefault(), "★ %.1f", it),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFB300)
                    )
                }
            }
        }
    }
}
