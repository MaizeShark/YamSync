package com.anisync.android.presentation.details.components

import com.anisync.android.ui.theme.ExpressiveShapes
import com.anisync.android.ui.theme.emphasis
import com.anisync.android.domain.url

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anisync.android.R
import com.anisync.android.domain.CharacterInfo
import com.anisync.android.domain.RecommendedMedia
import com.anisync.android.domain.RelatedMedia
import com.anisync.android.domain.StaffInfo
import com.anisync.android.presentation.components.ListIndicator
import com.anisync.android.presentation.components.ListIndicatorStyle
import com.anisync.android.presentation.util.AppMotion
import com.anisync.android.presentation.util.LocalLibraryStatuses
import com.anisync.android.presentation.util.TransitionKeys
import com.anisync.android.presentation.util.bouncyClickable
import com.anisync.android.presentation.util.bouncyCombinedClickable
import com.anisync.android.presentation.util.formatAsTitle
import com.anisync.android.presentation.util.rememberCopyToClipboard
import com.anisync.android.util.getName
import com.anisync.android.util.getTitle

/**
 * Portrait aspect for character/staff cards (matches the 100×140 fixed-cell dimens).
 * Used by the See-all grids so cells fill their column at a uniform ratio instead of
 * a fixed width that floats inside a wider adaptive cell (#83).
 */
private const val PERSON_PORTRAIT_ASPECT = 5f / 7f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CharacterItem(
    character: CharacterInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillCell: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val imageShape = ExpressiveShapes.mediaCover
    val copyToClipboard = rememberCopyToClipboard()
    val copyLabel = stringResource(R.string.a11y_action_copy)
    val nameClipLabel = stringResource(R.string.clip_label_character_name)
    val copiedNameMessage = stringResource(R.string.copied_name)

    // In a grid cell, fill the column width and hold a uniform portrait aspect; in the
    // horizontal preview rail, keep the natural fixed width so items scroll side by side.
    val widthModifier = if (fillCell) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.width(dimensionResource(R.dimen.character_item_width))
    }
    val imageSizeModifier = if (fillCell) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(PERSON_PORTRAIT_ASPECT)
    } else {
        Modifier
            .height(dimensionResource(R.dimen.character_image_height))
            .fillMaxWidth()
    }

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .then(widthModifier)
            .clip(imageShape)
            .bouncyCombinedClickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = stringResource(
                    R.string.a11y_action_open_details,
                    character.nameUserPreferred
                ),
                onLongClickLabel = copyLabel,
                onLongClick = {
                    copyToClipboard(nameClipLabel, character.nameUserPreferred, copiedNameMessage)
                }
            )
            .padding(bottom = dimensionResource(R.dimen.spacing_small))
    ) {
        val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val spatialSpec = AppMotion.rememberSpatialSpec()
            with(sharedTransitionScope) {
                imageSizeModifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = TransitionKeys.characterImage(
                                character.id
                            )
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> spatialSpec },
                        clipInOverlayDuringTransition = OverlayClip(imageShape)
                    )
                    .clip(imageShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            }
        } else {
            imageSizeModifier
                .clip(imageShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        }

        AsyncImage(
            model = character.imageUrl,
            contentDescription = stringResource(
                R.string.a11y_character_image,
                character.nameUserPreferred
            ),
            contentScale = ContentScale.Crop,
            modifier = imageModifier
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
        Text(
            text = character.nameUserPreferred,
            style = MaterialTheme.typography.labelMedium.emphasis(),
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = character.role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StaffItem(
    staff: StaffInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillCell: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val imageShape = ExpressiveShapes.mediaCover
    val copyToClipboard = rememberCopyToClipboard()
    val copyLabel = stringResource(R.string.a11y_action_copy)
    val nameClipLabel = stringResource(R.string.clip_label_staff_name)
    val copiedNameMessage = stringResource(R.string.copied_name)

    val roleText = staff.role.takeIf { it.isNotBlank() }
        ?: staff.primaryOccupations.firstOrNull().orEmpty()

    // In a grid cell, fill the column width and hold a uniform portrait aspect; in the
    // horizontal preview rail, keep the natural fixed width so items scroll side by side.
    val widthModifier = if (fillCell) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.width(dimensionResource(R.dimen.character_item_width))
    }
    val imageSizeModifier = if (fillCell) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(PERSON_PORTRAIT_ASPECT)
    } else {
        Modifier
            .height(dimensionResource(R.dimen.character_image_height))
            .fillMaxWidth()
    }

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .then(widthModifier)
            .clip(imageShape)
            .bouncyCombinedClickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = stringResource(
                    R.string.a11y_action_open_details,
                    staff.nameUserPreferred
                ),
                onLongClickLabel = copyLabel,
                onLongClick = {
                    copyToClipboard(nameClipLabel, staff.nameUserPreferred, copiedNameMessage)
                }
            )
            .padding(bottom = dimensionResource(R.dimen.spacing_small))
    ) {
        val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val spatialSpec = AppMotion.rememberSpatialSpec()
            with(sharedTransitionScope) {
                imageSizeModifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = TransitionKeys.staffImage(staff.id)
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> spatialSpec },
                        clipInOverlayDuringTransition = OverlayClip(imageShape)
                    )
                    .clip(imageShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            }
        } else {
            imageSizeModifier
                .clip(imageShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        }

        AsyncImage(
            model = staff.imageUrl,
            contentDescription = stringResource(
                R.string.a11y_staff_image,
                staff.nameUserPreferred
            ),
            contentScale = ContentScale.Crop,
            modifier = imageModifier
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
        Text(
            text = staff.nameUserPreferred,
            style = MaterialTheme.typography.labelMedium.emphasis(),
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary
        )
        if (roleText.isNotEmpty()) {
            Text(
                text = roleText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RelationItem(
    relation: RelatedMedia,
    onClick: () -> Unit,
    transitionPrefix: String = TransitionKeys.MEDIA_DETAILS,
    modifier: Modifier = Modifier,
    fillCell: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val imageShape = ExpressiveShapes.mediaCover

    val widthModifier = if (fillCell) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.width(dimensionResource(R.dimen.character_item_width))
    }
    val imageSizeModifier = if (fillCell) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(PERSON_PORTRAIT_ASPECT)
    } else {
        Modifier
            .height(dimensionResource(R.dimen.character_image_height))
            .fillMaxWidth()
    }

    Column(
        modifier = modifier
            .then(widthModifier)
            .clip(imageShape)
            .clickable(onClick = onClick)
            .padding(bottom = dimensionResource(R.dimen.spacing_small))
    ) {
        val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val spatialSpec = AppMotion.rememberSpatialSpec()
            with(sharedTransitionScope) {
                imageSizeModifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = TransitionKeys.cover(transitionPrefix, relation.id)
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> spatialSpec },
                        clipInOverlayDuringTransition = OverlayClip(imageShape)
                    )
                    .clip(imageShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            }
        } else {
            imageSizeModifier
                .clip(imageShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        }

        Box(modifier = Modifier.clip(imageShape)) {
            AsyncImage(
                model = relation.cover.url() ?: relation.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier
            )

            LocalLibraryStatuses.current[relation.id]?.let { status ->
                ListIndicator(
                    status = status,
                    type = null,
                    style = ListIndicatorStyle.Corner,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.spacing_small)))
        Text(
            text = relation.relationType.formatAsTitle() ?: relation.relationType,
            style = MaterialTheme.typography.labelSmall.emphasis(),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = relation.titleUserPreferred,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

