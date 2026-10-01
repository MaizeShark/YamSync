package com.anisync.android.di

import android.content.Context
import androidx.room.Room
import com.anisync.android.data.local.AppDatabase
import com.anisync.android.data.local.dao.AiringScheduleDao
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.dao.MediaItemDao
import com.anisync.android.data.local.dao.MediaThemesDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "anisync.db"
        )
            // Every table is a cache the next sync refills, so a schema change rebuilds the
            // database instead of carrying migrations (see AppDatabase).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideLibraryDao(database: AppDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideMediaItemDao(database: AppDatabase): MediaItemDao = database.mediaItemDao()

    @Provides
    fun provideAiringScheduleDao(database: AppDatabase): AiringScheduleDao = database.airingScheduleDao()

    @Provides
    fun provideMediaThemesDao(database: AppDatabase): MediaThemesDao = database.mediaThemesDao()
}
