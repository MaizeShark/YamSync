package com.anisync.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anisync.android.data.util.ApiError
import com.anisync.android.domain.CalendarRepository
import com.anisync.android.domain.Result as DomainResult
import com.anisync.android.widget.AiringTodayWidgetProvider
import com.anisync.android.widget.UpNextWidgetProvider
import com.anisync.android.widget.WeeklyCalendarWidgetProvider
import com.anisync.android.widget.core.WidgetRefresh
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Syncs the Yamtrack release calendar into Room, where the schedule widgets and the calendar screen
 * read it, then repaints the widgets.
 */
@HiltWorker
class AiringScheduleWorker @AssistedInject constructor(
    @Assisted val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val calendarRepository: CalendarRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val INITIAL_WORK_NAME = "AiringScheduleWorkerInitial"

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<AiringScheduleWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result = when (val result = calendarRepository.sync()) {
        is DomainResult.Success -> {
            WidgetRefresh.refresh(appContext, AiringTodayWidgetProvider::class.java)
            WidgetRefresh.refresh(appContext, UpNextWidgetProvider::class.java)
            WidgetRefresh.refresh(appContext, WeeklyCalendarWidgetProvider::class.java)
            Result.success()
        }
        is DomainResult.Error -> when (result.exception) {
            is ApiError.SessionExpired, is ApiError.ParseError -> Result.failure()
            else -> Result.retry()
        }
    }
}
