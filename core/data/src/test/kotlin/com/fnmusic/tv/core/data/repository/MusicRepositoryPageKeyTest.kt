package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.SortedPageListDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRepositoryPageKeyTest {
    @Test fun `page cache key isolates requested page sizes`() {
        assertEquals("artists:size=12", sizedPageSourceKey("artists", 12))
        assertNotEquals(sizedPageSourceKey("artists", 12), sizedPageSourceKey("artists", 50))
        assertThrows(IllegalArgumentException::class.java) { sizedPageSourceKey("albums", 0) }
    }

    @Test fun `requested television page size controls continuation`() {
        val response = SortedPageListDto(
            list = List(12) { "artist-$it" },
            total = 345,
        )

        val seventhPage = response.toDomainPage(page = 7, pageSize = 12) { it }
        val lastPage = response.toDomainPage(page = 29, pageSize = 12) { it }

        assertEquals(12, seventhPage.pageSize)
        assertTrue(seventhPage.hasNext)
        assertFalse(lastPage.hasNext)
    }
}
