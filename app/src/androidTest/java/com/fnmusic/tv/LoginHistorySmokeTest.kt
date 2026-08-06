package com.fnmusic.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fnmusic.tv.core.data.repository.LoginDraft
import com.fnmusic.tv.core.data.repository.LoginHistoryEntry
import com.fnmusic.tv.ui.FnMusicTheme
import com.fnmusic.tv.ui.LoginScreen
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LoginHistorySmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun historySelectionLogsInOnceAndDeleteRemainsIndependent() {
        val loginCount = AtomicInteger()
        val deleteCount = AtomicInteger()
        val selectedProfile = AtomicReference<String>()
        val entry = LoginHistoryEntry("profile-1", "10.0.0.115:5666", "test", useHttps = false)
        composeRule.setContent {
            FnMusicTheme {
                LoginScreen(
                    savedServer = "",
                    recentServers = emptyList(),
                    initialError = null,
                    onLogin = { _, _, _, password, _, accessCode ->
                        password.fill('\u0000')
                        accessCode.fill('\u0000')
                    },
                    loginHistory = listOf(entry),
                    onHistoryLogin = { profileId, accessCode, _ ->
                        accessCode?.fill('\u0000')
                        selectedProfile.set(profileId)
                        loginCount.incrementAndGet()
                    },
                    onHistoryDelete = { deleteCount.incrementAndGet() },
                    historyDraft = {
                        LoginDraft(
                            profileId = entry.id,
                            server = entry.server,
                            username = entry.username,
                            useHttps = entry.useHttps,
                            accessCode = "654321",
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("历史").performClick()
        composeRule.onNodeWithText("test").assertIsDisplayed()
        composeRule.onNodeWithText("10.0.0.115:5666 (HTTP)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("登录历史：test").performClick()
        composeRule.waitUntil(5_000) { loginCount.get() == 1 }

        assertEquals("profile-1", selectedProfile.get())
        assertEquals(0, deleteCount.get())
    }

    @Test fun historyDeleteDoesNotTriggerLogin() {
        val loginCount = AtomicInteger()
        val deleteCount = AtomicInteger()
        val entry = LoginHistoryEntry("profile-1", "nas.local", "test", useHttps = true)
        composeRule.setContent {
            FnMusicTheme {
                LoginScreen(
                    savedServer = "",
                    recentServers = emptyList(),
                    initialError = null,
                    onLogin = { _, _, _, password, _, accessCode ->
                        password.fill('\u0000')
                        accessCode.fill('\u0000')
                    },
                    loginHistory = listOf(entry),
                    onHistoryLogin = { _, accessCode, _ ->
                        accessCode?.fill('\u0000')
                        loginCount.incrementAndGet()
                    },
                    onHistoryDelete = { deleteCount.incrementAndGet() },
                    historyDraft = { null },
                )
            }
        }

        composeRule.onNodeWithContentDescription("历史").performClick()
        composeRule.onNodeWithContentDescription("删除历史：test").performClick()
        composeRule.waitUntil(5_000) { deleteCount.get() == 1 }

        assertEquals(0, loginCount.get())
    }
}
