package com.anisync.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anisync.android.domain.AnimeStatusCounts
import com.anisync.android.domain.GenreStat

/**
 * Room entity for caching user profile.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val about: String?,
    val activeAt: Long?,
    val animeCount: Int,
    val daysWatched: Float,
    val mangaCount: Int,
    val chaptersRead: Int,
    val meanScore: Float,
    val animeStatusCounts: AnimeStatusCounts = AnimeStatusCounts(),
    val lastUpdated: Long = System.currentTimeMillis(),
    
    @ColumnInfo(defaultValue = "[]")
    val topGenres: List<GenreStat> = emptyList(),

    @ColumnInfo(defaultValue = "0")
    val donatorTier: Int = 0,

    @ColumnInfo(defaultValue = "")
    val donatorBadge: String? = null,

    @ColumnInfo(defaultValue = "[]")
    val moderatorRoles: List<String> = emptyList(),

    @ColumnInfo(defaultValue = "0")
    val createdAt: Long? = null
)
