package com.anisync.android.util

import com.anisync.android.data.TitleLanguage
import com.anisync.android.data.local.entity.LibraryEntryEntity
import com.anisync.android.domain.LibraryEntry

/**
 * Yamtrack keeps one title per item, in whatever language its metadata source uses, so the title
 * language preference has nothing to choose between. Kept so callers stay put until it goes.
 */
@Suppress("UNUSED_PARAMETER")
fun LibraryEntry.getTitle(language: TitleLanguage): String = title

@Suppress("UNUSED_PARAMETER")
fun LibraryEntryEntity.getTitle(language: TitleLanguage): String = title
