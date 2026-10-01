package com.anisync.android.presentation.components

import com.anisync.android.domain.LibraryStatus
import com.anisync.android.ui.theme.ListIndicatorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ListIndicatorKindTest {

    @Test
    fun `each status maps to its own indicator`() {
        assertEquals(ListIndicatorKind.WATCHING, LibraryStatus.CURRENT.toIndicatorKind())
        assertEquals(ListIndicatorKind.PLANNING, LibraryStatus.PLANNING.toIndicatorKind())
        assertEquals(ListIndicatorKind.PAUSED, LibraryStatus.PAUSED.toIndicatorKind())
        assertEquals(ListIndicatorKind.COMPLETED, LibraryStatus.COMPLETED.toIndicatorKind())
        assertEquals(ListIndicatorKind.DROPPED, LibraryStatus.DROPPED.toIndicatorKind())
    }

    @Test
    fun `no two statuses share an indicator`() {
        val mapped = LibraryStatus.entries.map { it.toIndicatorKind() }
        assertEquals(mapped.size, mapped.toSet().size)
    }
}
