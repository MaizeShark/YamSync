package com.anisync.android.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Granular notification preferences.
 * Allows users to enable/disable specific notification types independently.
 */
@Singleton
class NotificationPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Watching list - new episodes for shows you're watching
    private val _watchingEnabled = MutableStateFlow(prefs.getBoolean(KEY_WATCHING_ENABLED, true))
    val watchingEnabled: StateFlow<Boolean> = _watchingEnabled.asStateFlow()

    // Planning list - premieres of shows you plan to watch
    private val _planningEnabled = MutableStateFlow(prefs.getBoolean(KEY_PLANNING_ENABLED, true))
    val planningEnabled: StateFlow<Boolean> = _planningEnabled.asStateFlow()

    // Upcoming - episodes airing soon
    private val _upcomingEnabled = MutableStateFlow(prefs.getBoolean(KEY_UPCOMING_ENABLED, true))
    val upcomingEnabled: StateFlow<Boolean> = _upcomingEnabled.asStateFlow()

    // Streaming availability delay (minutes) for "episode aired" notifications.
    // Lets users on streaming sites that post episodes after the official airing time
    // postpone the notification so it lines up with when the episode is actually watchable.
    private val _streamingDelayMinutes = MutableStateFlow(
        prefs.getInt(KEY_STREAMING_DELAY_MINUTES, 0).coerceIn(MIN_STREAMING_DELAY_MINUTES, MAX_STREAMING_DELAY_MINUTES)
    )
    val streamingDelayMinutes: StateFlow<Int> = _streamingDelayMinutes.asStateFlow()

    fun setWatchingEnabled(enabled: Boolean) {
        _watchingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WATCHING_ENABLED, enabled).apply()
    }

    fun setPlanningEnabled(enabled: Boolean) {
        _planningEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PLANNING_ENABLED, enabled).apply()
    }

    fun setUpcomingEnabled(enabled: Boolean) {
        _upcomingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_UPCOMING_ENABLED, enabled).apply()
    }

    fun setStreamingDelayMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_STREAMING_DELAY_MINUTES, MAX_STREAMING_DELAY_MINUTES)
        _streamingDelayMinutes.value = clamped
        prefs.edit().putInt(KEY_STREAMING_DELAY_MINUTES, clamped).apply()
    }

    /**
     * Reset all notification preferences to default (all enabled).
     */
    fun resetToDefaults() {
        setWatchingEnabled(true)
        setPlanningEnabled(true)
        setUpcomingEnabled(true)
        setStreamingDelayMinutes(0)
    }

    companion object {
        private const val PREFS_NAME = "notification_preferences"
        private const val KEY_WATCHING_ENABLED = "watching_enabled"
        private const val KEY_PLANNING_ENABLED = "planning_enabled"
        private const val KEY_UPCOMING_ENABLED = "upcoming_enabled"
        private const val KEY_STREAMING_DELAY_MINUTES = "streaming_delay_minutes"
        const val MIN_STREAMING_DELAY_MINUTES = 0
        const val MAX_STREAMING_DELAY_MINUTES = 180
    }
}
