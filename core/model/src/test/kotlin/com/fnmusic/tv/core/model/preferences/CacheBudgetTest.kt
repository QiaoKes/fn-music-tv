package com.fnmusic.tv.core.model.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheBudgetTest {
    @Test fun `budgets split media and artwork without preallocation`() {
        CacheBudget.entries.forEach { budget ->
            val total = budget.megabytes * 1024L * 1024L
            assertEquals(total * 3 / 4, budget.mediaBytes)
            assertEquals(total / 4, budget.artworkBytes)
            assertEquals(total, budget.mediaBytes + budget.artworkBytes)
        }
    }
}
