package com.anisync.android.data

import com.anisync.android.data.local.dao.AiringScheduleDao
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.entity.AiringScheduleEntity
import com.anisync.android.data.yamtrack.YamtrackGateway
import com.anisync.android.domain.AiringEpisode
import com.anisync.android.domain.CalendarRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaKeyRegistry
import com.anisync.android.domain.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Releases from the Yamtrack calendar, for every tracked type rather than only anime.
 *
 * A sync stores the feed (30 days back to 90 ahead) in `airing_schedule`, which the home-screen
 * widgets read; the calendar screen reads the same rows, so it works offline between syncs.
 */
@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val gateway: YamtrackGateway,
    private val airingScheduleDao: AiringScheduleDao,
    private val libraryDao: LibraryDao,
    private val registry: MediaKeyRegistry,
) : CalendarRepository {

    private val syncMutex = Mutex()
    private var lastSyncAt = 0L
    private var lastSyncOwner = -1

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        val owner = gateway.ownerId
        if (owner < 0) return@withLock Result.Error("Not signed in")
        when (val result = gateway.call { calendar() }) {
            is Result.Error -> result
            is Result.Success -> {
                val statuses = libraryDao.getAll(owner).associate { it.mediaKey to it.status }
                val rows = result.data.map { event ->
                    val mediaId = event.key?.let { registry.idFor(it, event.title, event.imageUrl) } ?: 0
                    AiringScheduleEntity(
                        id = event.uid.ifBlank { event.summary + event.startsAt }.hashCode(),
                        ownerId = owner,
                        mediaId = mediaId,
                        airingAt = event.startsAt / 1000,
                        episode = event.contentNumber ?: 0,
                        titleUserPreferred = event.title ?: event.summary,
                        coverUrl = event.imageUrl,
                        format = event.key?.type?.slug,
                        isWatching = event.key?.let { statuses[it.asString()] } == LibraryStatus.CURRENT
                    )
                }
                airingScheduleDao.clearAll(owner)
                airingScheduleDao.insertAll(rows)
                lastSyncAt = System.currentTimeMillis()
                lastSyncOwner = owner
                Result.Success(Unit)
            }
        }
    }

    override suspend fun getWeekSchedule(weekStartEpochSec: Long, weekEndEpochSec: Long): Result<List<AiringEpisode>> {
        val owner = gateway.ownerId
        val stale = owner != lastSyncOwner || System.currentTimeMillis() - lastSyncAt > SYNC_INTERVAL_MS
        if (stale) {
            val synced = sync()
            // Cached rows are still worth showing when the server cannot be reached.
            if (synced is Result.Error && airingScheduleDao.getAiringBetween(owner, weekStartEpochSec, weekEndEpochSec - 1).isEmpty()) {
                return synced
            }
        }
        val statuses = libraryDao.getAll(owner).associate { it.mediaId to it.status }
        val episodes = airingScheduleDao.getAiringBetween(owner, weekStartEpochSec, weekEndEpochSec - 1).map { row ->
            val status = statuses[row.mediaId]
            AiringEpisode(
                id = row.id,
                episode = row.episode,
                airingAt = row.airingAt,
                mediaId = row.mediaId,
                title = row.titleUserPreferred,
                coverImageUrl = row.coverUrl,
                format = row.format,
                isOnList = status != null,
                listStatus = status
            )
        }
        return Result.Success(episodes)
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 30 * 60 * 1000L
    }
}
