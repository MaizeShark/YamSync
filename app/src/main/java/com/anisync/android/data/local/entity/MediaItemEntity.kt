package com.anisync.android.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Gives every Yamtrack item a small, stable local id.
 *
 * Yamtrack names an item by source, type and the source's id (plus season and episode numbers),
 * which is awkward to carry through navigation routes, widgets, intents and notification ids. Those
 * use [id]; this table translates back. It also remembers the title and image last seen, so a screen
 * opened by id can draw its header before anything loads.
 */
@Entity(tableName = "media_items", indices = [Index(value = ["mediaKey"], unique = true)])
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val mediaKey: String,
    val title: String?,
    val imageUrl: String?,
    /** The item's total in its unit (episodes, chapters…), once anything has reported it. */
    val maxProgress: Int? = null
)
