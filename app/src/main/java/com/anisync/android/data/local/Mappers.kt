package com.anisync.android.data.local

import com.anisync.android.data.local.entity.LibraryEntryEntity
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.model.MediaKey

fun LibraryEntryEntity.toDomain(): LibraryEntry? {
    val key = MediaKey.parse(mediaKey) ?: return null
    return LibraryEntry(
        id = instanceId,
        mediaId = mediaId,
        key = key,
        title = title,
        coverUrl = coverUrl,
        progress = progress,
        maxProgress = maxProgress,
        status = status,
        score = score,
        startedAt = startedAt,
        completedAt = completedAt,
        notes = notes,
        rewatches = rewatches,
        createdAt = createdAt,
        updatedAt = updatedAt,
        customLists = customLists,
        nextAiringEpisode = nextAiringEpisode,
        nextAiringEpisodeTime = nextAiringEpisodeTime
    )
}

fun LibraryEntry.toEntity(ownerId: Int): LibraryEntryEntity = LibraryEntryEntity(
    ownerId = ownerId,
    mediaId = mediaId,
    instanceId = id,
    mediaKey = key.asString(),
    mediaType = key.type.slug,
    title = title,
    coverUrl = coverUrl,
    progress = progress,
    maxProgress = maxProgress,
    status = status,
    score = score,
    startedAt = startedAt,
    completedAt = completedAt,
    notes = notes,
    rewatches = rewatches,
    createdAt = createdAt,
    updatedAt = updatedAt,
    customLists = customLists,
    nextAiringEpisode = nextAiringEpisode,
    nextAiringEpisodeTime = nextAiringEpisodeTime
)
