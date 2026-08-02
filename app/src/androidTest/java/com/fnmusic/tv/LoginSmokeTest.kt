package com.fnmusic.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fnmusic.tv.ui.FnMusicTheme
import com.fnmusic.tv.ui.LoginScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LoginSmokeTest {
    @get:Rule val composeRule = createComposeRule()
    private val loginAttempts = AtomicInteger()

    @Before fun renderLogin() {
        loginAttempts.set(0)
        composeRule.setContent {
            FnMusicTheme {
                LoginScreen(
                    savedServer = "",
                    recentServers = emptyList(),
                    initialError = null,
                    onLogin = { _, _, _, password, _, accessCode ->
                        password.fill('\u0000')
                        accessCode.fill('\u0000')
                        loginAttempts.incrementAndGet()
                    },
                )
            }
        }
    }

    private fun enterField(description: String, text: String) {
        val field = composeRule.onNodeWithContentDescription(description)
        field.performTouchInput { click() }
        composeRule.waitForIdle()
        Thread.sleep(300)
        field.performTextInput(text)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()
    }

    private fun enterValidCredentials() {
        enterField("NAS 地址或 FNID", "10.0.0.115")
        enterField("账号", "test")
        enterField("密码", "a123456")
    }

    @Test fun loginSurfaceRendersRequiredControls() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("NAS 地址或 FNID")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("NAS 地址或 FNID").fetchSemanticsNode()
        composeRule.onNodeWithText("账号").fetchSemanticsNode()
        composeRule.onNodeWithText("密码").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("登录").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("账号").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("密码").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("安全码（未启用可留空）").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("保持登录").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("HTTPS").fetchSemanticsNode()
    }

    @Test fun loginDpadFocusGraphTraversesEveryControl() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("NAS 地址或 FNID")).fetchSemanticsNodes().isNotEmpty()
        }
        val server = composeRule.onNodeWithContentDescription("NAS 地址或 FNID")
        val username = composeRule.onNodeWithContentDescription("账号")
        val password = composeRule.onNodeWithContentDescription("密码")

        server.assertIsFocused()
        server.performKeyInput { pressKey(Key.DirectionDown) }
        username.assertIsFocused()
        username.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithContentDescription("安全码（未启用可留空）").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
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
            composeRule.onAllNodes(hasText("NAS 地址或 FNID")).fetchSemanticsNodes().isNotEmpty()
        }
        val server = composeRule.onNodeWithContentDescription("NAS 地址或 FNID")
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

    @Test fun touchingAFieldEntersEditingAndAcceptsText() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("账号")).fetchSemanticsNodes().isNotEmpty()
        }
        val username = composeRule.onNodeWithContentDescription("账号")

        username.performTouchInput { click() }
        composeRule.waitForIdle()
        Thread.sleep(300)
        username.assertIsFocused().performTextInput("touch-user")

        username.assertTextContains("touch-user")
    }

    @Test fun loginOptionUsesCheckboxState() {
        val rememberLogin = composeRule.onNodeWithContentDescription("保持登录")
        val checked = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
        val unchecked = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)

        rememberLogin.assert(checked).performClick()
        composeRule.waitForIdle()
        rememberLogin.assert(unchecked)
    }

    @Test fun loginButtonAcceptsPhysicalTouch() {
        enterValidCredentials()

        composeRule.onNodeWithContentDescription("登录")
            .assertIsEnabled()
            .performTouchInput { click() }
        composeRule.waitUntil(timeoutMillis = 5_000) { loginAttempts.get() == 1 }
    }

    @Test fun loginButtonStillAcceptsDpadCenter() {
        enterValidCredentials()

        composeRule.onNodeWithContentDescription("密码")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("保持登录")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("HTTPS")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("登录")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.waitUntil(timeoutMillis = 5_000) { loginAttempts.get() == 1 }
    }
}
