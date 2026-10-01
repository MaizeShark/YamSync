package com.anisync.android.data

import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.dao.MediaItemDao
import com.anisync.android.data.local.entity.LibraryEntryEntity
import com.anisync.android.data.local.toDomain
import com.anisync.android.data.local.toEntity
import com.anisync.android.data.yamtrack.YamtrackEntry
import com.anisync.android.data.yamtrack.YamtrackEntryFields
import com.anisync.android.data.yamtrack.YamtrackGateway
import com.anisync.android.data.yamtrack.YamtrackTrackForm
import com.anisync.android.domain.LibraryEntry
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.Result
import com.anisync.android.domain.model.MediaKey
import com.anisync.android.domain.model.MediaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library, cached in Room per account and read from Yamtrack.
 *
 * A refresh reads the CSV export (every row of every type, no side effects on the user's web
 * filters) and the home page (ids and totals for what is in progress). Room keeps the newest row per
 * item, with earlier rows counted as rewatches. Episode rows are not library entries; they belong to
 * their season.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    private val gateway: YamtrackGateway,
    private val libraryDao: LibraryDao,
    private val mediaItemDao: MediaItemDao,
    private val accountStore: AccountStore,
) : LibraryRepository {

    /** One refresh at a time; a second caller waits and then finds the cache fresh. */
    private val refreshMutex = Mutex()

    override fun observeLibrary(type: MediaType?): Flow<List<LibraryEntry>> =
        accountStore.activeAccount.flatMapLatest { account ->
            val owner = account?.id ?: -1
            if (type == null) libraryDao.observeAll(owner) else libraryDao.observeByType(owner, type.slug)
        }.map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeListStatuses(): Flow<Map<Int, LibraryStatus>> =
        accountStore.activeAccount.flatMapLatest { account ->
            libraryDao.observeListStatuses(account?.id ?: -1)
        }.map { rows -> rows.associate { it.mediaId to it.status } }

    override fun observeEntry(mediaId: Int): Flow<LibraryEntry?> =
        accountStore.activeAccount.flatMapLatest { account ->
            libraryDao.observeEntry(account?.id ?: -1, mediaId)
        }.map { it?.toDomain() }

    override suspend fun refreshLibrary(): Result<Unit> = refreshMutex.withLock {
        val owner = gateway.ownerId
        if (owner < 0) return@withLock Result.Error("Not signed in")
        when (val rows = gateway.call { libraryEntries() }) {
            is Result.Error -> rows
            is Result.Success -> {
                // The home page supplies the totals and ids the export lacks. Not having them is not
                // worth failing the refresh over.
                val home = (gateway.call { inProgressItems() } as? Result.Success)?.data.orEmpty()
                    .associateBy { it.key }
                val entities = rows.data
                    .filter { it.key.type != MediaType.EPISODE }
                    .groupBy { it.key }
                    .map { (key, group) ->
                        val newest = group.maxBy { it.createdAt ?: 0L }
                        val homeItem = home[key]
                        val mediaId = mediaItemDao.idFor(
                            mediaKey = key.asString(),
                            title = newest.title.ifBlank { null },
                            imageUrl = newest.imageUrl,
                            maxProgress = homeItem?.maxProgress
                        )
                        val known = mediaItemDao.getById(mediaId)
                        newest.toEntity(
                            ownerId = owner,
                            mediaId = mediaId,
                            instanceId = homeItem?.instanceId?.toInt() ?: 0,
                            maxProgress = homeItem?.maxProgress ?: known?.maxProgress,
                            rewatches = group.size - 1
                        )
                    }
                libraryDao.replaceAll(owner, entities)
                Result.Success(Unit)
            }
        }
    }

    override suspend fun updateProgress(mediaId: Int, progress: Int): Result<Unit> {
        val owner = gateway.ownerId
        val current = libraryDao.getEntry(owner, mediaId)?.toDomain()
            ?: return Result.Error("This entry is not in the library")
        if (!current.type.hasEditableProgress) return Result.Success(Unit)
        val max = current.maxProgress
        val clamped = progress.coerceAtLeast(0).let { if (max != null) it.coerceAtMost(max) else it }
        val updated = current.copy(
            progress = clamped,
            // Yamtrack completes an entry that reaches its total; mirror it so the UI does not flicker.
            status = if (max != null && clamped >= max && current.status == LibraryStatus.CURRENT) {
                LibraryStatus.COMPLETED
            } else {
                current.status
            },
            updatedAt = System.currentTimeMillis()
        )
        return when (val result = updateEntry(updated)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> result
        }
    }

    override suspend fun updateEntry(entry: LibraryEntry): Result<LibraryEntry> {
        val owner = gateway.ownerId
        val previous = libraryDao.getEntry(owner, entry.mediaId)
        libraryDao.insertOrReplace(entry.toEntity(owner))
        val result = gateway.call {
            val instanceId = entry.id.takeIf { it > 0 }?.toLong() ?: trackForm(entry.key).instanceId
            saveEntry(entry.key, instanceId, entry.toFields())
        }
        return when (result) {
            is Result.Success -> {
                val saved = entry.withForm(result.data)
                libraryDao.insertOrReplace(saved.toEntity(owner))
                Result.Success(saved)
            }
            is Result.Error -> {
                if (previous != null) libraryDao.insertOrReplace(previous) else libraryDao.deleteByMediaId(owner, entry.mediaId)
                result
            }
        }
    }

    override suspend fun addEntry(key: MediaKey, status: LibraryStatus, title: String?, imageUrl: String?): Result<LibraryEntry> {
        val owner = gateway.ownerId
        val mediaId = mediaItemDao.idFor(key.asString(), title, imageUrl)
        val known = mediaItemDao.getById(mediaId)
        val now = System.currentTimeMillis()
        val draft = LibraryEntry(
            id = 0,
            mediaId = mediaId,
            key = key,
            title = title ?: known?.title.orEmpty(),
            coverUrl = imageUrl ?: known?.imageUrl,
            progress = 0,
            maxProgress = known?.maxProgress,
            status = status,
            createdAt = now,
            updatedAt = now
        )
        libraryDao.insertOrReplace(draft.toEntity(owner))
        return when (val result = gateway.call { saveEntry(key, null, draft.toFields()) }) {
            is Result.Success -> {
                val saved = draft.withForm(result.data)
                libraryDao.insertOrReplace(saved.toEntity(owner))
                Result.Success(saved)
            }
            is Result.Error -> {
                libraryDao.deleteByMediaId(owner, mediaId)
                result
            }
        }
    }

    override suspend fun addRewatch(entry: LibraryEntry): Result<LibraryEntry> {
        val owner = gateway.ownerId
        val now = System.currentTimeMillis()
        val fresh = entry.copy(
            id = 0,
            progress = 0,
            status = LibraryStatus.CURRENT,
            score = null,
            startedAt = now,
            completedAt = null,
            notes = null,
            rewatches = entry.rewatches + 1,
            createdAt = now,
            updatedAt = now
        )
        return when (val result = gateway.call { saveEntry(entry.key, null, fresh.toFields()) }) {
            is Result.Success -> {
                val saved = fresh.withForm(result.data)
                libraryDao.insertOrReplace(saved.toEntity(owner))
                Result.Success(saved)
            }
            is Result.Error -> result
        }
    }

    override suspend fun deleteEntry(entry: LibraryEntry): Result<Unit> {
        val owner = gateway.ownerId
        val result = gateway.call {
            val instanceId = entry.id.takeIf { it > 0 }?.toLong() ?: trackForm(entry.key).instanceId
            if (instanceId != null) deleteEntry(entry.key.type, instanceId)
            // An earlier watch, if there was one, is now the newest entry.
            trackForm(entry.key)
        }
        return when (result) {
            is Result.Success -> {
                val remaining = result.data
                if (remaining.instanceId == null) {
                    libraryDao.deleteByMediaId(owner, entry.mediaId)
                } else {
                    val previous = entry.copy(rewatches = (entry.rewatches - 1).coerceAtLeast(0)).withForm(remaining)
                    libraryDao.insertOrReplace(previous.toEntity(owner))
                }
                Result.Success(Unit)
            }
            is Result.Error -> result
        }
    }

    private fun LibraryEntry.toFields() = YamtrackEntryFields(
        status = status,
        score = score,
        progress = progress.takeIf { type.hasEditableProgress },
        startDate = startedAt,
        endDate = completedAt,
        notes = notes
    )

    /** This entry as the server stored it. */
    private fun LibraryEntry.withForm(form: YamtrackTrackForm) = copy(
        id = form.instanceId?.toInt() ?: id,
        status = form.fields.status,
        score = form.fields.score,
        progress = form.fields.progress ?: progress,
        startedAt = form.fields.startDate,
        completedAt = form.fields.endDate,
        notes = form.fields.notes
    )

    private fun YamtrackEntry.toEntity(
        ownerId: Int,
        mediaId: Int,
        instanceId: Int,
        maxProgress: Int?,
        rewatches: Int
    ) = LibraryEntryEntity(
        ownerId = ownerId,
        mediaId = mediaId,
        instanceId = instanceId,
        mediaKey = key.asString(),
        mediaType = key.type.slug,
        title = title,
        coverUrl = imageUrl,
        progress = progress ?: 0,
        maxProgress = maxProgress,
        status = status ?: LibraryStatus.PLANNING,
        score = score,
        startedAt = startDate,
        completedAt = endDate,
        notes = notes,
        rewatches = rewatches,
        createdAt = createdAt,
        updatedAt = progressedAt ?: createdAt
    )
}
