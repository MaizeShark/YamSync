package com.anisync.android.worker

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.anisync.android.R

object NotificationChannels {
    const val AIRING_CHANNEL_ID = "airing_notifications"
    const val PLANNING_CHANNEL_ID = "planning_notifications"
    const val UPCOMING_CHANNEL_ID = "upcoming_notifications"
    const val UPDATE_CHANNEL_ID = "update_notifications"

    private const val GROUP_AIRING_ID = "group_airing"
    private const val GROUP_OTHER_ID = "group_other"

    private data class ChannelSpec(
        val id: String,
        val nameRes: Int,
        val descriptionRes: Int,
        val groupId: String,
    )

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(GROUP_AIRING_ID, context.getString(R.string.notification_group_airing)),
                NotificationChannelGroup(GROUP_OTHER_ID, context.getString(R.string.notification_group_other)),
            )
        )

        val specs = listOf(
            ChannelSpec(AIRING_CHANNEL_ID, R.string.notification_channel_watching, R.string.notification_channel_watching_desc, GROUP_AIRING_ID),
            ChannelSpec(PLANNING_CHANNEL_ID, R.string.notification_channel_planning, R.string.notification_channel_planning_desc, GROUP_AIRING_ID),
            ChannelSpec(UPCOMING_CHANNEL_ID, R.string.notification_channel_upcoming, R.string.notification_channel_upcoming_desc, GROUP_AIRING_ID),
            ChannelSpec(UPDATE_CHANNEL_ID, R.string.update_notification_channel_name, R.string.update_notification_channel_desc, GROUP_OTHER_ID),
        )

        val channels = specs.map { spec ->
            // A channel's group is frozen at creation, so channels from versions before the groups
            // existed sit ungrouped ("Other" bucket in system settings) forever. Delete those once;
            // recreating under the same id revives them inside their group.
            val existing = notificationManager.getNotificationChannel(spec.id)
            if (existing != null && existing.group == null) {
                notificationManager.deleteNotificationChannel(spec.id)
            }
            NotificationChannel(
                spec.id,
                context.getString(spec.nameRes),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(spec.descriptionRes)
                group = spec.groupId
            }
        }

        notificationManager.createNotificationChannels(channels)
    }
}
