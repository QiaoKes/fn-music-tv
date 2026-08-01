package com.fnmusic.tv.core.model.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheBudgetTest {
    @Test fun `budgets are artwork-only tiers with stable persisted names`() {
        assertEquals(listOf("Small", "Medium", "Default", "Large"), CacheBudget.entries.map(CacheBudget::name))
        assertEquals(listOf(32, 64, 128, 256), CacheBudget.entries.map(CacheBudget::megabytes))
        CacheBudget.entries.forEach { budget ->
            assertEquals(budget.megabytes * 1024L * 1024L, budget.artworkBytes)
        }
    }

    @Test fun `usage combines artwork and metadata bytes`() {
        val usage = CacheUsage(artworkBytes = 12L, indexBytes = 7L)

        assertEquals(19L, usage.totalBytes)
    }
}
