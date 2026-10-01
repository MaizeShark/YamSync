package com.anisync.android.domain

import com.anisync.android.domain.model.MediaKey

/**
 * Translates between Yamtrack's item keys and the small local ids the app passes around in
 * navigation routes, widgets, intents and notifications.
 */
interface MediaKeyRegistry {
    /** The local id for [key], created on first sight; [title] and [imageUrl] refresh what is known. */
    suspend fun idFor(key: MediaKey, title: String? = null, imageUrl: String? = null, maxProgress: Int? = null): Int

    suspend fun keyFor(id: Int): MediaKey?

    /** What is known about the item behind [id] without asking the server. */
    suspend fun summary(id: Int): MediaSummary?
}

/** The little the app remembers about any item it has shown. */
data class MediaSummary(
    val id: Int,
    val key: MediaKey,
    val title: String?,
    val imageUrl: String?,
    val maxProgress: Int?
)
