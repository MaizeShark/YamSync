package com.anisync.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anisync.android.data.util.ApiError
import com.anisync.android.domain.LibraryRepository
import com.anisync.android.widget.core.WidgetRefresh
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import com.anisync.android.domain.Result as DomainResult

/**
 * Fetches the whole Yamtrack library, then repaints the widgets.
 *
 * Widgets read Room and never the network, so an account that never opened the library has no rows
 * at all. The widgets ask for a sync when they find nothing and get repainted once the rows land.
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Callers that only want the first fill stop here once the library has rows.
        if (inputData.getBoolean(KEY_ONLY_IF_EMPTY, false) &&
            libraryRepository.observeLibrary().first().isNotEmpty()
        ) {
            return Result.success()
        }

        return when (val result = libraryRepository.refreshLibrary()) {
            is DomainResult.Success -> {
                WidgetRefresh.all(appContext)
                Result.success()
            }
            is DomainResult.Error -> when (result.exception) {
                is ApiError.SessionExpired, is ApiError.ParseError -> Result.failure()
                else -> Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_ONLY_IF_EMPTY = "only_if_empty"
        private const val WORK_NAME = "library_sync"

        /** Fills a library that has never been synced on this account, and does nothing otherwise. */
        fun enqueueIfEmpty(context: Context) {
            enqueue(context, onlyIfEmpty = true)
        }

        /**
         * Asks for a library sync, one in flight at most.
         *
         * KEEP not REPLACE: the widget asks on every render while the list is empty, and replacing
         * would restart the fetch each time and never finish it.
         */
        fun enqueue(context: Context, onlyIfEmpty: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_ONLY_IF_EMPTY, onlyIfEmpty).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
