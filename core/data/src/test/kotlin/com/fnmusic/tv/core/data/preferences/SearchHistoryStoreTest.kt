package com.fnmusic.tv.core.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class SearchHistoryStoreTest {
    private lateinit var store: SearchHistoryStore

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = SearchHistoryStore(application)
    }

    @Test fun `record keeps newest first without duplicates`() {
        store.record("那英")
        store.record("周杰伦")
        store.record("那英")

        assertEquals(listOf("那英", "周杰伦"), store.state.value)
    }

    @Test fun `record ignores blank and trims surrounding spaces`() {
        store.record("   ")
        store.record("  那英  ")

        assertEquals(listOf("那英"), store.state.value)
    }

    @Test fun `record is case insensitive on dedupe`() {
        store.record("Queen")
        store.record("queen")

        assertEquals(listOf("queen"), store.state.value)
    }

    @Test fun `history is bounded to the newest entries`() {
        (1..20).forEach { store.record("key-$it") }

        assertEquals((20 downTo 9).map { "key-$it" }, store.state.value)
    }

    @Test fun `history survives a new store instance`() {
        store.record("那英")
        store.record("孙燕姿")

        val reloaded = SearchHistoryStore(
            ApplicationProvider.getApplicationContext(),
        )

        assertEquals(listOf("孙燕姿", "那英"), reloaded.state.value)
    }

    @Test fun `clear empties memory and persistence`() {
        store.record("那英")
        store.clear()

        val reloaded = SearchHistoryStore(ApplicationProvider.getApplicationContext())

        assertEquals(emptyList<String>(), reloaded.state.value)
    }
}
