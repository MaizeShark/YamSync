package com.anisync.android.presentation.details.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anisync.android.R
import com.anisync.android.presentation.components.AsyncRichTextRenderer
import com.anisync.android.presentation.components.ReadMoreToggle
import com.anisync.android.presentation.components.TranslateIconButton
import com.anisync.android.ui.theme.emphasis

/**
 * Clamps [content] to [collapsedHeight] unless [expanded], reporting whether it overflows. Measuring
 * rather than counting lines up front means the toggle appears exactly when the parsed content
 * turns out to be long.
 */
@Composable
private fun ClampedBody(
    expanded: Boolean,
    collapsedHeight: Dp,
    onOverflowChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val maxHeightPx = with(LocalDensity.current) { collapsedHeight.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                onOverflowChange(placeable.height > maxHeightPx)
                val height = if (!expanded && placeable.height > maxHeightPx) {
                    maxHeightPx.toInt()
                } else {
                    placeable.height
                }
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpandableSynopsis(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val cardShape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_extra_large))
    Surface(
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .animateContentSize(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
            )
    ) {
        Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_medium))) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.section_synopsis),
                    style = MaterialTheme.typography.labelSmall.emphasis(),
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                TranslateIconButton(
                    text = text,
                    iconSize = 18.dp,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))

            // The renderer already wraps its content in a SelectionContainer, so wrapping again
            // here would nest (and crash) one; collapse goes through a clamped height box instead.
            var overflows by remember { mutableStateOf(false) }
            ClampedBody(
                expanded = expanded,
                collapsedHeight = 110.dp,
                onOverflowChange = { overflows = it }
            ) {
                AsyncRichTextRenderer(
                    html = text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            if (overflows || expanded) {
                Spacer(Modifier.height(dimensionResource(R.dimen.spacing_normal)))

                ReadMoreToggle(
                    expanded = expanded,
                    onToggle = { expanded = !expanded }
                )
            }
        }
    }
}
