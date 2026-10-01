package com.anisync.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anisync.android.data.local.dao.AiringScheduleDao
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.dao.MediaItemDao
import com.anisync.android.data.local.dao.MediaThemesDao
import com.anisync.android.data.local.entity.AiringScheduleEntity
import com.anisync.android.data.local.entity.LibraryEntryEntity
import com.anisync.android.data.local.entity.MediaItemEntity
import com.anisync.android.data.local.entity.MediaThemesEntity

/**
 * Room database for offline caching. Every table is a cache of something on the Yamtrack server
 * (or AnimeThemes), so a schema change may rebuild the database rather than migrate it: the next
 * sync fills it again.
 *
 * Version history starts over at 30 with the move from AniList to Yamtrack; versions up to 29 were
 * the AniList client's and are dropped on upgrade.
 */
@Database(
    entities = [
        LibraryEntryEntity::class,
        MediaItemEntity::class,
        AiringScheduleEntity::class,
        MediaThemesEntity::class
    ],
    version = 30,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun airingScheduleDao(): AiringScheduleDao
    abstract fun mediaThemesDao(): MediaThemesDao
}
