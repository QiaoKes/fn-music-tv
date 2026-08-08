package com.fnmusic.tv.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.core.data.local.AppDatabase
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.preferences.CacheBudget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var localStore: LocalStore

    @Before fun setUp() {
        context.deleteDatabase(AppDatabase.NAME)
        context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        localStore = LocalStore(context)
    }

    @After fun tearDown() {
        localStore.database.close()
        context.deleteDatabase(AppDatabase.NAME)
    }

    @Test fun `binding an existing account syncs service facing preferences`() = runBlocking {
        localStore.saveSettings("server:user", PlayerStyle.Cover.name, CacheBudget.Small.name)
        val shared = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        shared.edit()
            .putString("player_style", PlayerStyle.Poster.name)
            .putString("cache_budget", CacheBudget.Large.name)
            .commit()

        val preferences = AppPreferences(context, localStore)
        preferences.bindNamespace("server:user")

        assertEquals(PlayerStyle.Cover, preferences.state.value.playerStyle)
        assertEquals(CacheBudget.Small, preferences.state.value.cacheBudget)
        assertEquals(PlayerStyle.Cover.name, shared.getString("player_style", null))
        assertEquals(CacheBudget.Small.name, shared.getString("cache_budget", null))
    }

    @Test fun `online lyric matching defaults on and restores the account setting`() = runBlocking {
        assertTrue(AppPreferences(context, localStore).state.value.onlineLyricsMatchingEnabled)
        localStore.saveSettings(
            namespace = "server:user",
            style = PlayerStyle.Poster.name,
            budget = CacheBudget.Default.name,
            onlineLyricsMatchingEnabled = false,
        )

        val preferences = AppPreferences(context, localStore)
        preferences.bindNamespace("server:user")

        assertFalse(preferences.state.value.onlineLyricsMatchingEnabled)
        assertFalse(
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                .getBoolean("online_lyrics_matching", true),
        )
    }
}
