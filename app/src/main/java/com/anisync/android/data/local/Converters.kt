package com.anisync.android.data.local

import androidx.room.TypeConverter
import com.anisync.android.domain.LibraryStatus
import com.anisync.android.domain.MediaTheme
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromLibraryStatus(value: String): LibraryStatus =
        runCatching { LibraryStatus.valueOf(value) }.getOrDefault(LibraryStatus.PLANNING)

    @TypeConverter
    fun toLibraryStatus(status: LibraryStatus): String = status.name

    @TypeConverter
    fun fromStringList(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun toStringList(list: List<String>): String = json.encodeToString(list)

    @TypeConverter
    fun fromMediaThemeList(value: String): List<MediaTheme> =
        runCatching { json.decodeFromString<List<MediaTheme>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun toMediaThemeList(list: List<MediaTheme>): String = json.encodeToString(list)
}
