package com.fnmusic.tv.update

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatePreferencesTest {
    private lateinit var preferences: UpdatePreferences

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("device_update_preferences", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        preferences = UpdatePreferences(context)
    }

    @Test fun `ignore is device global and exact by version code`() {
        preferences.ignore(22)

        assertTrue(preferences.isIgnored(22))
        assertFalse(preferences.isIgnored(23))
    }

    @Test fun `cleanup removes only records lower than installed version`() {
        preferences.ignore(21)
        preferences.ignore(22)
        preferences.ignore(24)

        preferences.cleanBelow(22)

        assertFalse(preferences.isIgnored(21))
        assertTrue(preferences.isIgnored(22))
        assertTrue(preferences.isIgnored(24))
    }
}
