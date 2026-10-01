package com.anisync.android.data

import com.anisync.android.data.local.dao.MediaItemDao
import com.anisync.android.domain.MediaKeyRegistry
import com.anisync.android.domain.MediaSummary
import com.anisync.android.domain.model.MediaKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaKeyRegistryImpl @Inject constructor(
    private val dao: MediaItemDao
) : MediaKeyRegistry {

    override suspend fun idFor(key: MediaKey, title: String?, imageUrl: String?, maxProgress: Int?): Int =
        dao.idFor(key.asString(), title, imageUrl, maxProgress)

    override suspend fun keyFor(id: Int): MediaKey? = dao.getById(id)?.mediaKey?.let(MediaKey::parse)

    override suspend fun summary(id: Int): MediaSummary? {
        val item = dao.getById(id) ?: return null
        val key = MediaKey.parse(item.mediaKey) ?: return null
        return MediaSummary(item.id, key, item.title, item.imageUrl, item.maxProgress)
    }
}
