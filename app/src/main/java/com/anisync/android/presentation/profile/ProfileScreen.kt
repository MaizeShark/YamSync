package com.anisync.android.presentation.profile

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.anisync.android.presentation.components.AppCircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anisync.android.R
import com.anisync.android.presentation.components.ErrorState
import com.anisync.android.presentation.login.AniListAuth
import com.anisync.android.presentation.profile.components.AccountSwitcherSheet

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProfileScreen(
    onMediaClick: (Int) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateToSettings: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollToTopRequest by viewModel.scrollToTopRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccountId by viewModel.activeAccountId.collectAsStateWithLifecycle()
    var showAccountSwitcherSheet by remember { mutableStateOf(false) }

    // Cooldown-gated ON_RESUME refresh. The 60s floor prevents quick app-switch from re-firing a refresh; the
    // ViewModel's profileCooldown (15s) is the second line of defence.
    // rememberSaveable (not remember): survives this screen's composition being
    // disposed on navigation. Otherwise the 60s gate resets to 0 on every back-nav
    // and the ON_RESUME refresh re-fires, re-fetching the active tab and resetting
    // scroll position.
    val lastResumeAtMs = rememberSaveable { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResumeAtMs.longValue > 60_000L) {
            viewModel.onAction(ProfileAction.Refresh(forceNetwork = false))
        }
        lastResumeAtMs.longValue = now
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val profile = uiState.profile
            val errorText = uiState.errorMessage
                ?: uiState.loadErrorRes?.let { stringResource(it) }
            when {
                uiState.isLoading && profile == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AppCircularProgressIndicator()
                    }
                }

                errorText != null && profile == null -> {
                    ErrorState(
                        message = errorText,
                        onRetry = { viewModel.onAction(ProfileAction.Refresh()) }
                    )
                }

                profile != null -> {
                    ProfileContent(
                        profile = profile,
                        uiState = uiState,
                        scrollToTopRequest = scrollToTopRequest,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAction = viewModel::onAction,
                        onSettingsClick = onNavigateToSettings,
                        onMediaClick = onMediaClick,
                        showAccountSwitcher = accounts.size > 1,
                        onAccountSwitchClick = { showAccountSwitcherSheet = true }
                    )
                }

                else -> {
                    ErrorState(
                        message = stringResource(R.string.profile_unknown_error),
                        onRetry = { viewModel.onAction(ProfileAction.Refresh()) }
                    )
                }
            }
        }
    }

    if (showAccountSwitcherSheet) {
        AccountSwitcherSheet(
            accounts = accounts,
            activeAccountId = activeAccountId,
            onSwitch = { id ->
                showAccountSwitcherSheet = false
                viewModel.switchAccount(id)
            },
            onAddAccount = {
                showAccountSwitcherSheet = false
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AniListAuth.AUTH_URL)))
            },
            onDismiss = { showAccountSwitcherSheet = false }
        )
    }
}
