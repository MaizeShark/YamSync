package com.anisync.android.worker

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
import com.anisync.android.data.NotificationPreferences
import com.anisync.android.data.account.AccountStore
import com.anisync.android.data.local.dao.AiringScheduleDao
import com.anisync.android.data.local.dao.LibraryDao
import com.anisync.android.data.local.entity.AiringScheduleEntity
import com.anisync.android.data.util.ApiError
import com.anisync.android.domain.CalendarRepository
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.PreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.anisync.android.domain.Result as DomainResult

/**
 * Local release notifications, built from the Yamtrack calendar.
 *
 * Yamtrack has no push or inbox, so every run syncs the active account's calendar into
 * `airing_schedule` and compares it with the cached library:
 * - a release of something in progress that came out since the last run ("Episode 5 is out"),
 * - the first release of something planned (with an "Add to Watching" action),
 * - an advance and an imminent notice before that first release.
 *
 * Each notice is remembered per account by key, so reruns never repeat one. An account's first run
 * only records what is already out, otherwise the whole past month would arrive at once.
 */
@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val calendarRepository: CalendarRepository,
    private val preferencesRepository: PreferencesRepository,
    private val libraryDao: LibraryDao,
    private val airingScheduleDao: AiringScheduleDao,
    private val imageLoader: ImageLoader,
    private val accountStore: AccountStore,
    private val notificationPreferences: NotificationPreferences
) : CoroutineWorker(appContext, workerParams) {

    /** Per-run account context threaded through the checks and notification builders. */
    private data class AcctCtx(
        val id: Int,
        val name: String,
        val showLabel: Boolean,
    )

    companion object {
        private const val TAG = "NotificationWorker"
        // Two-tier upcoming notification system
        private const val ADVANCE_NOTICE_HOURS = 12 // First notification: "Episode 1 airs tomorrow at X"
        private const val IMMINENT_NOTICE_HOURS = 2  // Second notification: "Episode 1 airs in 2 hours"

        /** How far back a release still counts as new; older ones are only recorded. */
        private const val RELEASE_LOOKBACK_SECONDS = 3 * 24 * 3600L

        private const val GROUP_KEY_WATCHING = "com.anisync.android.WATCHING_GROUP"
        private const val GROUP_KEY_PLANNING = "com.anisync.android.PLANNING_GROUP"

        // Tray slot = (tag "acct_<accountId>_<category>", id stable per target). A newer event for
        // the same target replaces its stale tray entry instead of piling up next to it.
        private const val CATEGORY_WATCHING = "watching"
        private const val CATEGORY_PLANNING = "planning"
        private const val CATEGORY_UPCOMING = "upcoming"
    }

    override suspend fun doWork(): Result {
        // The calendar and library cache belong to the active account, so only it is checked.
        val account = accountStore.activeAccount.value
        if (account == null || account.isExpired) {
            Log.d(TAG, "No usable account, skipping notification check")
            return Result.success()
        }
        val ctx = AcctCtx(account.id, account.name, accountStore.accounts.value.size > 1)

        when (val synced = calendarRepository.sync()) {
            is DomainResult.Success -> Unit
            is DomainResult.Error -> return when (synced.exception) {
                is ApiError.SessionExpired -> {
                    Log.w(TAG, "Session expired for ${ctx.name}")
                    Result.success()
                }
                else -> {
                    Log.w(TAG, "Calendar sync failed: ${synced.message}")
                    Result.retry()
                }
            }
        }

        val now = System.currentTimeMillis() / 1000
        val delay = notificationPreferences.streamingDelayMinutes.value * 60L
        val releases = airingScheduleDao.getAiringBetween(ctx.id, now - RELEASE_LOOKBACK_SECONDS, now + ADVANCE_NOTICE_HOURS * 3600L)
        val statuses = libraryDao.getAll(ctx.id).associate { it.mediaId to it.status }

        if (!preferencesRepository.hasNotificationsEverRun(ctx.id)) {
            // First run for this account: remember what is already out without notifying.
            releases.filter { it.airingAt + delay <= now }.forEach {
                preferencesRepository.markNotifiedWithKey(ctx.id, releasedKey(it))
            }
            preferencesRepository.markNotificationsHaveRun(ctx.id)
            return Result.success()
        }

        for (release in releases) {
            if (release.mediaId == 0) continue
            val status = statuses[release.mediaId] ?: continue
            try {
                when {
                    release.airingAt + delay <= now -> notifyReleased(release, status, ctx)
                    status == LibraryStatus.PLANNING && release.episode <= 1 &&
                        notificationPreferences.upcomingEnabled.value -> notifyUpcoming(release, now, ctx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Notification for ${release.titleUserPreferred} failed", e)
            }
        }
        return Result.success()
    }

    private suspend fun notifyReleased(release: AiringScheduleEntity, status: LibraryStatus, ctx: AcctCtx) {
        val key = releasedKey(release)
        if (preferencesRepository.hasNotifiedWithKey(ctx.id, key)) return
        when {
            status == LibraryStatus.CURRENT && notificationPreferences.watchingEnabled.value ->
                showReleasedNotification(release, ctx)
            status == LibraryStatus.PLANNING && release.episode <= 1 && notificationPreferences.planningEnabled.value ->
                showPlanningFirstEpisodeNotification(release, ctx)
        }
        preferencesRepository.markNotifiedWithKey(ctx.id, key)
    }

    private suspend fun notifyUpcoming(release: AiringScheduleEntity, now: Long, ctx: AcctCtx) {
        val hoursUntil = ((release.airingAt - now) / 3600).toInt()
        val imminentKey = "imminent_${release.id}"
        val advanceKey = "advance_${release.id}"
        when {
            hoursUntil < IMMINENT_NOTICE_HOURS -> if (!preferencesRepository.hasNotifiedWithKey(ctx.id, imminentKey)) {
                showImminentEpisodeNotification(release, hoursUntil, ctx)
                preferencesRepository.markNotifiedWithKey(ctx.id, imminentKey)
                preferencesRepository.markNotifiedWithKey(ctx.id, advanceKey)
            }
            hoursUntil < ADVANCE_NOTICE_HOURS -> if (!preferencesRepository.hasNotifiedWithKey(ctx.id, advanceKey)) {
                showAdvanceEpisodeNotification(release, ctx)
                preferencesRepository.markNotifiedWithKey(ctx.id, advanceKey)
            }
        }
    }

    private fun releasedKey(release: AiringScheduleEntity) = "released_${release.id}"

    private fun string(resId: Int, vararg args: Any): String =
        applicationContext.getString(resId, *args)

    private fun quantityString(resId: Int, quantity: Int, vararg args: Any): String =
        applicationContext.resources.getQuantityString(resId, quantity, *args)

    private suspend fun showReleasedNotification(release: AiringScheduleEntity, ctx: AcctCtx) {
        val content = if (release.episode > 0) {
            string(R.string.notification_release_number_out, release.episode)
        } else {
            string(R.string.notification_release_out)
        }
        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.AIRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(release.titleUserPreferred)
            .setContentText(content)
            .setAutoCancel(true)
            .setWhen(release.airingAt * 1000L)
            .setShowWhen(true)
            .setGroup(groupKey(GROUP_KEY_WATCHING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${release.mediaId}", ctx, release.id))
        release.coverUrl?.let { loadImage(it) }?.let(builder::setLargeIcon)
        post(ctx, CATEGORY_WATCHING, release.mediaId, builder)
    }

    private suspend fun showAdvanceEpisodeNotification(release: AiringScheduleEntity, ctx: AcctCtx) {
        val airingDate = java.util.Date(release.airingAt * 1000)
        val formattedTime = DateFormat.getTimeFormat(applicationContext).format(airingDate)

        val calendar = java.util.Calendar.getInstance()
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        calendar.time = airingDate
        val airingDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)

        val dayPrefix = when (airingDay) {
            currentDay -> string(R.string.notification_day_today)
            currentDay + 1 -> string(R.string.notification_day_tomorrow)
            else -> string(R.string.notification_day_on_date, DateFormat.getDateFormat(applicationContext).format(airingDate))
        }
        val content = string(R.string.notification_episode_one_airs_at, dayPrefix, formattedTime)
        showUpcoming(release, content, ctx)
    }

    private suspend fun showImminentEpisodeNotification(release: AiringScheduleEntity, hoursUntil: Int, ctx: AcctCtx) {
        val content = if (hoursUntil < 1) {
            string(R.string.notification_episode_one_airs_under_hour)
        } else {
            quantityString(R.plurals.notification_episode_one_airs_in_hours, hoursUntil, hoursUntil)
        }
        showUpcoming(release, content, ctx)
    }

    /** Advance and imminent share one tray slot: "starting soon" replaces "airs tomorrow". */
    private suspend fun showUpcoming(release: AiringScheduleEntity, content: String, ctx: AcctCtx) {
        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.UPCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(release.titleUserPreferred)
            .setContentText(content)
            .setAutoCancel(true)
            .setGroup(groupKey(GROUP_KEY_PLANNING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${release.mediaId}", ctx, release.id))
        release.coverUrl?.let { loadImage(it) }?.let(builder::setLargeIcon)
        post(ctx, CATEGORY_UPCOMING, release.id, builder)
    }

    private suspend fun showPlanningFirstEpisodeNotification(release: AiringScheduleEntity, ctx: AcctCtx) {
        val notificationId = release.mediaId
        val addToWatchingIntent = Intent(applicationContext, AddToWatchingReceiver::class.java).apply {
            action = AddToWatchingReceiver.ACTION_ADD_TO_WATCHING
            putExtra(AddToWatchingReceiver.EXTRA_MEDIA_ID, release.mediaId)
            putExtra(AddToWatchingReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(AddToWatchingReceiver.EXTRA_NOTIFICATION_TAG, notificationTag(ctx, CATEGORY_PLANNING))
            putExtra(AddToWatchingReceiver.EXTRA_MEDIA_TITLE, release.titleUserPreferred)
        }
        val addToWatchingPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId + 1000000, // Unique request code to avoid conflicts
            addToWatchingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, NotificationChannels.PLANNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(release.titleUserPreferred)
            .setContentText(string(R.string.notification_episode_one_available))
            .setAutoCancel(true)
            .setWhen(release.airingAt * 1000L)
            .setShowWhen(true)
            .setGroup(groupKey(GROUP_KEY_PLANNING, ctx))
            .setContentIntent(deepLinkIntent("anisync://details/${release.mediaId}", ctx, notificationId))
            .addAction(
                R.drawable.ic_notification,
                string(R.string.notification_action_add_to_watching),
                addToWatchingPendingIntent
            )
        release.coverUrl?.let { loadImage(it) }?.let(builder::setLargeIcon)
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
     * Posts under a per-account, per-category tag, so two accounts can't overwrite each other.
     * Labels the account when more than one is signed in.
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
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }
}
