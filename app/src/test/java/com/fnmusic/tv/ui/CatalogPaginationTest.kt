package com.fnmusic.tv.ui

import com.fnmusic.tv.core.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPaginationTest {
    @Test
    fun `catalog pages keep exactly one screen of entries`() {
        val entries = (1..30).toList()

        assertEquals((1..12).toList(), catalogPageEntries(entries, page = 1, pageSize = 12))
        assertEquals((13..24).toList(), catalogPageEntries(entries, page = 2, pageSize = 12))
        assertEquals((25..30).toList(), catalogPageEntries(entries, page = 3, pageSize = 12))
    }

    @Test
    fun `catalog page count uses the server total before all entries are loaded`() {
        assertEquals(3, catalogPageCount(total = 36, loadedCount = 20, pageSize = 12))
        assertEquals(1, catalogPageCount(total = 0, loadedCount = 0, pageSize = 12))
        assertEquals(2, catalogPageCount(total = null, loadedCount = 13, pageSize = 12))
    }

    @Test
    fun `catalog prefetches when the visible page reaches loaded data`() {
        assertFalse(shouldPrefetchCatalogContinuation(1, pageSize = 12, loadedCount = 20, hasNext = true))
        assertTrue(shouldPrefetchCatalogContinuation(2, pageSize = 12, loadedCount = 20, hasNext = true))
        assertFalse(shouldPrefetchCatalogContinuation(2, pageSize = 12, loadedCount = 20, hasNext = false))
    }

    @Test
    fun `retained page keeps the server total for the television pager`() {
        val retained = retainLoadedPage(
            current = RetainedPageSnapshot<String>(),
            loaded = Page(
                items = listOf("a", "b"),
                page = 1,
                pageSize = 20,
                total = 36,
                sort = "name",
            ),
            key = { it },
        )

        assertEquals(36, retained.total)
    }

    @Test
    fun `bottom row routes to an available pager action`() {
        assertEquals(
            CatalogPagerTarget.Next,
            catalogPagerTarget(column = 0, columns = 4, canPrevious = false, canNext = true),
        )
        assertEquals(
            CatalogPagerTarget.Previous,
            catalogPagerTarget(column = 3, columns = 4, canPrevious = true, canNext = false),
        )
        assertEquals(
            CatalogPagerTarget.Previous,
            catalogPagerTarget(column = 1, columns = 4, canPrevious = true, canNext = true),
        )
        assertEquals(
            CatalogPagerTarget.Next,
            catalogPagerTarget(column = 2, columns = 4, canPrevious = true, canNext = true),
        )
        assertEquals(
            null,
            catalogPagerTarget(column = 0, columns = 4, canPrevious = false, canNext = false),
        )
    }
}
