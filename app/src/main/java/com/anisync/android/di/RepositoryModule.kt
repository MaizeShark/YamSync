package com.anisync.android.di

import com.anisync.android.data.CalendarRepositoryImpl
import com.anisync.android.data.LibraryRepositoryImpl
import com.anisync.android.data.MediaKeyRegistryImpl
import com.anisync.android.data.MediaRepositoryImpl
import com.anisync.android.data.MediaThemesRepositoryImpl
import com.anisync.android.data.repository.PreferencesRepositoryImpl
import com.anisync.android.domain.CalendarRepository
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.domain.MediaKeyRegistry
import com.anisync.android.domain.MediaRepository
import com.anisync.android.domain.MediaThemesRepository
import com.anisync.android.domain.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    abstract fun bindMediaKeyRegistry(impl: MediaKeyRegistryImpl): MediaKeyRegistry

    @Binds
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    @Binds
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository

    @Binds
    abstract fun bindMediaThemesRepository(impl: MediaThemesRepositoryImpl): MediaThemesRepository
}
