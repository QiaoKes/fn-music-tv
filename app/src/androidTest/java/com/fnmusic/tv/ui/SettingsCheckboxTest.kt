package com.fnmusic.tv.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsCheckboxTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun dpadCenterAndTouchEachToggleExactlyOnce() {
        val toggles = AtomicInteger(0)
        composeRule.setContent {
            FnMusicTheme {
                var selected by remember { mutableStateOf(false) }
                val focusRequester = remember { FocusRequester() }
                val inputModeManager = LocalInputModeManager.current
                LaunchedEffect(Unit) {
                    yield()
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    focusRequester.requestFocus()
                }
                SettingsCheckbox(
                    label = "在线歌词匹配",
                    selected = selected,
                    onClick = {
                        toggles.incrementAndGet()
                        selected = !selected
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }

        val checkbox = composeRule.onNodeWithContentDescription("在线歌词匹配")
        checkbox.assertIsFocused().assertIsOff().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, toggles.get()) }
        checkbox.assertIsOn()

        checkbox.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(2, toggles.get()) }
        checkbox.assertIsOff()
    }
}
