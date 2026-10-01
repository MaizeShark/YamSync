package com.anisync.android.presentation.onboarding.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anisync.android.presentation.login.LoginScreen

/**
 * The Yamtrack sign-in form, raised over the welcome step. Signing in changes the active account,
 * which the onboarding ViewModel watches to move on by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier
    ) {
        LoginScreen()
    }
}
