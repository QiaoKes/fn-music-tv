package com.fnmusic.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fnmusic.tv.ui.FnMusicTheme
import com.fnmusic.tv.ui.LoginScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LoginSmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Before fun renderLogin() {
        composeRule.setContent {
            FnMusicTheme {
                LoginScreen(
                    savedServer = "",
                    recentServers = emptyList(),
                    initialError = null,
                    onLogin = { _, _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }
    }

    @Test fun loginSurfaceRendersRequiredControls() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("NAS 地址")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("NAS 地址").fetchSemanticsNode()
        composeRule.onNodeWithText("账号").fetchSemanticsNode()
        composeRule.onNodeWithText("密码").fetchSemanticsNode()
        composeRule.onNodeWithText("登录").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("账号").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("密码").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("保持登录").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("HTTPS").fetchSemanticsNode()
    }

    @Test fun loginDpadFocusGraphTraversesEveryControl() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("NAS 地址")).fetchSemanticsNodes().isNotEmpty()
        }
        val server = composeRule.onNodeWithContentDescription("NAS 地址")
        val username = composeRule.onNodeWithContentDescription("账号")
        val password = composeRule.onNodeWithContentDescription("密码")

        server.assertIsFocused()
        server.performKeyInput { pressKey(Key.DirectionDown) }
        username.assertIsFocused()
        username.performKeyInput { pressKey(Key.DirectionDown) }
        password.assertIsFocused()
        password.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithContentDescription("显示或隐藏密码").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        password.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("保持登录").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("HTTPS").assertIsFocused()
    }

    @Test fun serverEditingReturnsToBrowseBeforeDpadNavigation() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("NAS 地址")).fetchSemanticsNodes().isNotEmpty()
        }
        val server = composeRule.onNodeWithContentDescription("NAS 地址")
        val username = composeRule.onNodeWithContentDescription("账号")

        server.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        Thread.sleep(300)
        server.performTextInput("nas.local")
        server.assertTextContains("nas.local")

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()
        Thread.sleep(200)
        server.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        username.assertIsFocused()
    }
}
