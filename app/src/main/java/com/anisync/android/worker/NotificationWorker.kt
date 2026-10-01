package com.anisync.android.worker

import com.anisync.android.data.network.RequestPriority
import com.anisync.android.data.network.withRequestPriority
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.anisync.android.R
import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.util.ApiError
import com.anisync.android.domain.AiringSchedule
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.NotificationRepository
import com.anisync.android.domain.PreferencesRepository
import com.anisync.android.type.MediaType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.anisync.android.domain.Result as DomainResult

@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val preferencesRepository: PreferencesRepository,
    private val libraryDao: LibraryDao,
    private val imageLoader: ImageLoader,
    private val accountStore: AccountStore,
    private val notificationPreferences: com.anisync.android.data.NotificationPreferences
) : CoroutineWorker(appContext, workerParams) {

    /** Per-iteration account context threaded through the checks + notification builders. */
    private data class AcctCtx(
        val id: Int,
        val token: String,
        val name: String,
        val showLabel: Boolean,
    )

    companion object {
        private const val TAG = "NotificationWorker"
        // Two-tier upcoming notification system
        private const val ADVANCE_NOTICE_HOURS = 12 // First notification: "Episode 1 airs tomorrow at X"
        private const val IMMINENT_NOTICE_HOURS = 2  // Second notification: "Episode 1 airs in 2 hours"

        private const val GROUP_KEY_PLANNING = "com.anisync.android.PLANNING_GROUP"

        // Tray slot = (tag "acct_<accountId>_<category>", id stable per target). A newer event for
        // the same target replaces its stale tray entry instead of piling up next to it.
        private const val CATEGORY_PLANNING = "planning"
        private const val CATEGORY_UPCOMING = "upcoming"
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result =
        // Every request this run makes yields to whatever the user is doing. When the
        // budget is short the gate refuses instead of waiting, and WorkManager reruns us.
        withRequestPriority(RequestPriority.Background) { pollAccounts() }

    private suspend fun pollAccounts(): androidx.work.ListenableWorker.Result {
        // Poll every signed-in account that still has a (non-expired) token.
        val accounts = accountStore.accounts.value.filterNot { it.isExpired }
        if (accounts.isEmpty()) {
            Log.d(TAG, "No usable accounts — skipping notification check")
            return androidx.work.ListenableWorker.Result.success()
        }

        val activeId = accountStore.activeAccount.value?.id
        val showLabel = accounts.size > 1

        for (account in accounts) {
            val ctx = AcctCtx(account.id, account.token, account.name, showLabel)
            val isActive = account.id == activeId
            try {
                if (!preferencesRepository.hasNotificationsEverRun(ctx.id)) {
                    // First time we see this account — establish baselines silently. Only mark the
                    // baseline done when it fully succeeded, otherwise a failed first fetch would
                    // flood the user with that account's entire history on the next run.
                    if (performBaselineSync(ctx, isActive)) {
                        preferencesRepository.markNotificationsHaveRun(ctx.id)
                        Log.d(TAG, "Baseline sync done for ${ctx.name}")
                    } else {
                        Log.w(TAG, "Baseline sync incomplete for ${ctx.name} — will retry next run")
                    }
                    continue
                }

                // Planning / upcoming "Episode 1" alerts depend on the locally-cached library,
                // so they only run for the active account. The "has started" check must run first:
                // it consumes the upcoming-notified markers that checkUpcomingPlanningEpisodes
                // prunes once an episode leaves the upcoming window.
                if (isActive) {
                    if (notificationPreferences.planningEnabled.value) checkPlanningFirstEpisodes(ctx)
                    if (notificationPreferences.upcomingEnabled.value) checkUpcomingPlanningEpisodes(ctx)
                }
            } catch (e: ApiError.RateLimited) {
                // Back off the whole worker; per-account dedup makes the retry idempotent.
                Log.w(TAG, "Rate limited on ${ctx.name} (wait ${e.retryAfterSeconds}s) — retrying worker")
                return androidx.work.ListenableWorker.Result.retry()
            } catch (e: ApiError.Deferred) {
                // The gate is holding the budget for the user. Nothing was sent, so hand the whole
                // run back to WorkManager instead of waiting it out on a wakelock.
                Log.i(TAG, "Deferred on ${ctx.name} (wait ${e.retryAfterSeconds}s), rescheduling")
                return androidx.work.ListenableWorker.Result.retry()
            } catch (e: ApiError.TokenRejected) {
                // This account's token expired/revoked. Flag only it, keep polling the rest.
                Log.w(TAG, "Token rejected for ${ctx.name}, marking account expired")
                accountStore.markExpired(ctx.id)
            } catch (e: ApiError.SessionExpired) {
                Log.w(TAG, "Session expired for ${ctx.name}, marking account expired")
                accountStore.markExpired(ctx.id)
            } catch (e: Exception) {
                // One account failing must not abort the others.
                Log.e(TAG, "Notification check failed for ${ctx.name}", e)
            }
        }

        return androidx.work.ListenableWorker.Result.success()
    }

    /**
     * safeApiCall folds ApiErrors into Result.Error, so rate-limit/auth conditions never reach
     * doWork() as exceptions on their own — resurface the two it reacts to (retry / mark expired).
     */
    private fun DomainResult.Error.rethrowWorkerSignals() {
        when (val e = exception) {
            is ApiError.RateLimited,
            is ApiError.Deferred,
            is ApiError.TokenRejected,
            is ApiError.SessionExpired,
            -> throw e

            else -> Unit
        }
    }

    /**
     * On an account's first run, fetch current state and set baselines WITHOUT notifying, so the
     * user isn't spammed with that account's historical notifications. Planning/upcoming baseline
     * runs only for the active account (it uses the locally-cached library).
     *
     * @return true when every baseline fetch succeeded.
     */
    private suspend fun performBaselineSync(ctx: AcctCtx, isActive: Boolean): Boolean {
        var complete = true

        if (!isActive) return complete

        val planningEntries = libraryDao.getByType(ctx.id, MediaType.ANIME)
            .filter { it.status == LibraryStatus.PLANNING }
        val planningMediaIds = planningEntries.map { it.mediaId }

        when (val airedResult = notificationRepository.getFirstEpisodeAirings(planningMediaIds)) {
            is DomainResult.Success -> {
                for (airing in airedResult.data) {
                    preferencesRepository.markPlanningMediaAsNotified(ctx.id, airing.mediaId)
                }
            }
            is DomainResult.Error -> {
                airedResult.rethrowWorkerSignals()
                complete = false
            }
        }
        when (val upcomingResult = notificationRepository.getUpcomingFirstEpisodes(planningMediaIds, ADVANCE_NOTICE_HOURS)) {
            is DomainResult.Success -> {
                for (airing in upcomingResult.data) {
                    preferencesRepository.markUpcomingAiringNotified(ctx.id, airing.id)
                }
            }
            is DomainResult.Error -> {
                upcomingResult.rethrowWorkerSignals()
                complete = false
            }
        }
        return complete
    }

    private fun string(resId: Int, vararg args: Any): String =
        applicationContext.getString(resId, *args)

    private fun quantityString(resId: Int, quantity: Int, vararg args: Any): String =
        applicationContext.resources.getQuantityString(resId, quantity, *args)

    /**
     * Check for upcoming Episode 1 airings for Planning list items (active account only).
     * Two-tier: 12h advance, then 2h imminent.
     */
    private suspend fun checkUpcomingPlanningEpisodes(ctx: AcctCtx) {
        val planningEntries = libraryDao.getByType(ctx.id, MediaType.ANIME)
            .filter { it.status == LibraryStatus.PLANNING }
        if (planningEntries.isEmpty()) return

        val mediaIds = planningEntries.map { it.mediaId }
        val currentTimeSeconds = System.currentTimeMillis() / 1000

        when (val result = notificationRepository.getUpcomingFirstEpisodes(mediaIds, ADVANCE_NOTICE_HOURS)) {
            is DomainResult.Success -> {
                val upcomingAirings = result.data
                val currentAiringIds = upcomingAirings.map { it.id }.toSet()
                preferencesRepository.cleanupOldUpcomingAirings(ctx.id, currentAiringIds)

                for (airing in upcomingAirings) {
                    val hoursUntil = ((airing.airingAt - currentTimeSeconds) / 3600).toInt()
                    when {
                        hoursUntil <= IMMINENT_NOTICE_HOURS -> {
                            val imminentKey = "imminent_${airing.id}"
                            if (!preferencesRepository.hasNotifiedWithKey(ctx.id, imminentKey)) {
                                showImminentEpisodeNotification(airing, hoursUntil, ctx)
                                preferencesRepository.markNotifiedWithKey(ctx.id, imminentKey)
                                preferencesRepository.markUpcomingAiringNotified(ctx.id, airing.id)
                            }
                        }
                        hoursUntil <= ADVANCE_NOTICE_HOURS -> {
                            val advanceKey = "advance_${airing.id}"
                            if (!preferencesRepository.hasNotifiedWithKey(ctx.id, advanceKey)) {
                                showAdvanceEpisodeNotification(airing, ctx)
                                preferencesRepository.markNotifiedWithKey(ctx.id, advanceKey)
                                preferencesRepository.markUpcomingAiringNotified(ctx.id, airing.id)
                            }
                        }
                    }
                }
            }
            is DomainResult.Error -> {
                result.rethrowWorkerSignals()
                Log.e(TAG, "Failed to fetch upcoming episodes: ${result.message}", result.exception)
            }
        }
    }

    /**
     * Check for already-aired Episode 1 for Planning list items (active account only).
     */
    private suspend fun checkPlanningFirstEpisodes(ctx: AcctCtx) {
        val planningEntries = libraryDao.getByType(ctx.id, MediaType.ANIME)
            .filter { it.status == LibraryStatus.PLANNING }
        if (planningEntries.isEmpty()) return

        val mediaIds = planningEntries.map { it.mediaId }
        val notifiedIds = preferencesRepository.getNotifiedPlanningMediaIds(ctx.id)
        val unnotifiedIds = mediaIds.filter { it !in notifiedIds }
        if (unnotifiedIds.isEmpty()) return

        val upcomingNotifiedAiringIds = preferencesRepository.getNotifiedUpcomingAiringIds(ctx.id)

        when (val result = notificationRepository.getFirstEpisodeAirings(unnotifiedIds)) {
            is DomainResult.Success -> {
                for (airing in result.data) {
                    if (airing.id in upcomingNotifiedAiringIds) {
                        // Already told the user this premiere was coming — don't repeat it.
                        preferencesRepository.markPlanningMediaAsNotified(ctx.id, airing.mediaId)
                        continue
                    }
                    showPlanningFirstEpisodeNotification(airing, ctx)
                    preferencesRepository.markPlanningMediaAsNotified(ctx.id, airing.mediaId)
                }
            }
            is DomainResult.Error -> {
                result.rethrowWorkerSignals()
                Log.e(TAG, "Failed to fetch planning first episodes: ${result.message}", result.exception)
            }
        }
    }

    private suspend fun showAdvanceEpisodeNotification(airing: AiringSchedule, ctx: AcctCtx) {
        // Same slot as the imminent tier: "starting soon" replaces "airs tomorrow" in the tray.
        val notificationId = airing.id
        val title = airing.mediaTitle

        val airingDate = java.util.Date(airing.airingAt * 1000)
        val timeFormat = DateFormat.getTimeFormat(applicationContext)
        val formattedTime = timeFormat.format(airingDate)

        val calendar = java.util.Calendar.getInstance()
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        calendar.time = airingDate
        val airingDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)

        val dayPrefix = when {
            airingDay == currentDay -> string(R.string.notification_day_today)
            airingDay == currentDay + 1 -> string(R.string.notification_day_tomorrow)
            else -> {
                val dateFormat = DateFormat.getDateFormat(applicationContext)
                string(R.string.notification_day_on_date, dateFormat.format(airingDate))
            }
        }
        val content = string(R.string.notification_episode_one_airs_at, dayPrefix, formattedTime)

        val largeIcon: Bitmap? = airing.mediaCoverUrl?.let { loadImage(it) }

        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.UPCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setGroup(groupKey(GROUP_KEY_PLANNING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${airing.mediaId}", ctx, notificationId))

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        post(ctx, CATEGORY_UPCOMING, notificationId, builder)
        Log.d(TAG, "Sent advance notification for ${airing.mediaTitle}: $content")
    }

    private suspend fun showImminentEpisodeNotification(airing: AiringSchedule, hoursUntil: Int, ctx: AcctCtx) {
        val notificationId = airing.id
        val title = airing.mediaTitle

        val content = if (hoursUntil < 1) {
            string(R.string.notification_episode_one_airs_under_hour)
        } else {
            quantityString(
                R.plurals.notification_episode_one_airs_in_hours,
                hoursUntil,
                hoursUntil
            )
        }

        val largeIcon: Bitmap? = airing.mediaCoverUrl?.let { loadImage(it) }

        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.UPCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setGroup(groupKey(GROUP_KEY_PLANNING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${airing.mediaId}", ctx, notificationId))

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        post(ctx, CATEGORY_UPCOMING, notificationId, builder)
        Log.d(TAG, "Sent imminent notification for ${airing.mediaTitle}: $content")
    }

    private suspend fun showPlanningFirstEpisodeNotification(airing: AiringSchedule, ctx: AcctCtx) {
        val notificationId = airing.mediaId
        val title = airing.mediaTitle
        val content = string(R.string.notification_episode_one_available)

        // "Add to Watching" action button
        val addToWatchingIntent = Intent(applicationContext, AddToWatchingReceiver::class.java).apply {
            action = AddToWatchingReceiver.ACTION_ADD_TO_WATCHING
            putExtra(AddToWatchingReceiver.EXTRA_MEDIA_ID, airing.mediaId)
            putExtra(AddToWatchingReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(AddToWatchingReceiver.EXTRA_NOTIFICATION_TAG, notificationTag(ctx, CATEGORY_PLANNING))
            putExtra(AddToWatchingReceiver.EXTRA_MEDIA_TITLE, airing.mediaTitle)
        }
        val addToWatchingPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId + 1000000, // Unique request code to avoid conflicts
            addToWatchingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon: Bitmap? = airing.mediaCoverUrl?.let { loadImage(it) }

        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.PLANNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setWhen(airing.airingAt * 1000L)
            .setShowWhen(true)
            .setGroup(groupKey(GROUP_KEY_PLANNING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${airing.mediaId}", ctx, notificationId))
            .addAction(
                R.drawable.ic_notification,
                string(R.string.notification_action_add_to_watching),
                addToWatchingPendingIntent
            )

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        post(ctx, CATEGORY_PLANNING, notificationId, builder)
    }

    // ── Multi-account notification helpers ──────────────────────────────────────────────

    /** Group key salted per account so each account's notifications group under their own summary. */
    private fun groupKey(base: String, ctx: AcctCtx) = "$base.${ctx.id}"

    /** Deep link tagged with the account so a tap can switch to it first (see MainActivity). */
    private fun deepLinkIntent(uri: String, ctx: AcctCtx, requestCode: Int): PendingIntent {
        val withAccount = if (uri.contains('?')) "$uri&account=${ctx.id}" else "$uri?account=${ctx.id}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(withAccount))
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationTag(ctx: AcctCtx, category: String) = "acct_${ctx.id}_$category"

    /**
     * Posts under a per-account, per-category tag: two accounts can't overwrite each other, and
     * within an account the id only has to be unique per category (it's the target's own id, so
     * a newer event for the same target replaces the stale entry). Labels the account when more
     * than one is signed in.
     */
    private fun post(ctx: AcctCtx, category: String, id: Int, builder: NotificationCompat.Builder) {
        if (ctx.showLabel) builder.setSubText(ctx.name)
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationTag(ctx, category), id, builder.build())
    }

    private suspend fun loadImage(url: String): Bitmap? {
        val request = ImageRequest.Builder(applicationContext)
            .data(url)
            .size(256, 256)
            .allowHardware(false)
            .build()

        val result = imageLoader.execute(request)
        return if (result is SuccessResult) {
            result.drawable.toBitmap()
        } else {
            null
        }
    }
}
