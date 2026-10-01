package com.anisync.android.presentation.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.anisync.android.R
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.model.MediaType

/** How a type is consumed, which decides whether "in progress" reads as watching, reading or playing. */
private enum class Consumption { WATCH, READ, PLAY }

private val MediaType?.consumption: Consumption
    get() = when (this) {
        MediaType.MANGA, MediaType.BOOK, MediaType.COMIC -> Consumption.READ
        MediaType.GAME, MediaType.BOARDGAME -> Consumption.PLAY
        else -> Consumption.WATCH
    }

@StringRes
fun LibraryStatus.labelRes(type: MediaType?): Int = when (this) {
    LibraryStatus.CURRENT -> when (type.consumption) {
        Consumption.WATCH -> R.string.status_watching
        Consumption.READ -> R.string.status_reading
        Consumption.PLAY -> R.string.status_playing
    }
    LibraryStatus.PLANNING -> R.string.status_planning
    LibraryStatus.COMPLETED -> R.string.status_completed
    LibraryStatus.PAUSED -> R.string.status_paused
    LibraryStatus.DROPPED -> R.string.status_dropped
}

@Composable
fun LibraryStatus.toLabel(type: MediaType?): String = stringResource(labelRes(type))

/** Sentinel tab id for the synthetic, always-first "All" library tab (union of every status list). */
const val LIBRARY_ALL_TAB_ID = "all"

/**
 * Resolves a library tab identifier — [LIBRARY_ALL_TAB_ID], "status:<NAME>", or a raw custom-list
 * name — to its display label. Single source of truth shared by the tab row, the manage-lists sheet,
 * and the search category chips.
 */
@Composable
fun libraryTabLabel(tabId: String, type: MediaType?): String = when {
    tabId == LIBRARY_ALL_TAB_ID -> stringResource(R.string.all)
    tabId.startsWith("status:") -> {
        val statusName = tabId.removePrefix("status:")
        LibraryStatus.entries.find { it.name == statusName }?.toLabel(type) ?: statusName
    }
    else -> tabId
}

/** Status accent color, shared by list rows and badges. */
fun LibraryStatus.toColor(): Color = when (this) {
    LibraryStatus.CURRENT -> Color(0xFF4CAF50)
    LibraryStatus.COMPLETED -> Color(0xFF2196F3)
    LibraryStatus.PLANNING -> Color(0xFF9C27B0)
    LibraryStatus.PAUSED -> Color(0xFFFF9800)
    LibraryStatus.DROPPED -> Color(0xFFF44336)
}

fun LibraryStatus.toIcon(type: MediaType?): ImageVector = when (this) {
    LibraryStatus.CURRENT -> when (type.consumption) {
        Consumption.READ -> Icons.AutoMirrored.Filled.MenuBook
        Consumption.PLAY -> Icons.Default.SportsEsports
        Consumption.WATCH -> Icons.Default.PlayArrow
    }
    LibraryStatus.PLANNING -> Icons.Default.Event
    LibraryStatus.COMPLETED -> Icons.Default.Check
    LibraryStatus.DROPPED -> Icons.Default.Delete
    LibraryStatus.PAUSED -> Icons.Default.Pause
}

/** The status glyph for "add to a list", where no status is chosen yet. */
val AddToListIcon: ImageVector get() = Icons.Default.Add

/**
 * The list glyphs from the design file, drawn from the exported paths rather than the Material set.
 *
 * These differ from [toIcon] on purpose: a bookmark for Planning, a clock for Paused and a cross
 * for Dropped read as "which list" rather than "what happens if I tap this".
 */
@Composable
fun LibraryStatus.toListIcon(): ImageVector = ImageVector.vectorResource(toListIconRes())

@DrawableRes
fun LibraryStatus.toListIconRes(): Int = when (this) {
    LibraryStatus.CURRENT -> R.drawable.ic_list_watching
    LibraryStatus.PLANNING -> R.drawable.ic_list_planning
    LibraryStatus.PAUSED -> R.drawable.ic_list_paused
    LibraryStatus.COMPLETED -> R.drawable.ic_list_completed
    LibraryStatus.DROPPED -> R.drawable.ic_list_dropped
}

/**
 * Formats a snake_case string (e.g., "SOME_STATUS") to Title Case (e.g., "Some Status").
 * Returns null if the input is null.
 */
fun String?.formatAsTitle(): String? {
    if (this == null) return null
    return this.replace("_", " ")
        .split(" ")
        .joinToString(" ") { word ->
            when (word.uppercase()) {
                "TV", "OVA", "ONA" -> word.uppercase()
                else -> word.lowercase().replaceFirstChar { char -> char.uppercase() }
            }
        }
}
