package com.fnmusic.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.yield

class PlayerLyricsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun coverLyricsKeepTranslationCompactAndScrollWithPlayback() {
        assertCompactScrollingLyrics(poster = false)
    }

    @Test fun posterLyricsKeepTranslationCompactAndScrollWithPlayback() {
        assertCompactScrollingLyrics(poster = true)
    }

    @Test fun lyricLinesStayOutOfTheDpadFocusGraph() {
        composeRule.setContent {
            FnMusicTheme {
                val buttonFocus = remember { FocusRequester() }
                val inputModeManager = LocalInputModeManager.current
                LaunchedEffect(Unit) {
                    yield()
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    buttonFocus.requestFocus()
                }
                Column {
                    TvLyrics(testLyrics, 0L, null, loading = false, failed = false, poster = false)
                    Button(
                        onClick = {},
                        modifier = Modifier.focusRequester(buttonFocus),
                    ) { androidx.tv.material3.Text("播放控制") }
                }
            }
        }

        composeRule.onNodeWithText("播放控制").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
            .assertIsFocused()
    }

    @Test fun longLyricsWrapInsideTheAvailableWidth() {
        val longLine = "这是一句用于验证电视歌词布局的很长歌词，它需要在可用宽度内自然换行而不能越过播放器边界"
        composeRule.setContent {
            FnMusicTheme {
                Box(Modifier.width(520.dp).testTag("lyrics-host")) {
                    TvLyrics(
                        SyncedLyrics(listOf(SyncedLine(longLine, null, 0, 4_000))),
                        0L,
                        null,
                        loading = false,
                        failed = false,
                        poster = false,
                    )
                }
            }
        }

        val host = composeRule.onNodeWithTag("lyrics-host").fetchSemanticsNode().boundsInRoot
        val text = boundsOf(longLine)
        assertTrue(text.left >= host.left)
        assertTrue(text.right <= host.right)
        assertTrue(text.height > with(composeRule.density) { 32.dp.toPx() })
    }

    private fun assertCompactScrollingLyrics(poster: Boolean) {
        lateinit var positionMs: MutableState<Long>
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FnMusicTheme {
                positionMs = rememberTestState(0L)
                TvLyrics(
                    lyrics = testLyrics,
                    positionMs = positionMs.value,
                    staticLyric = null,
                    loading = false,
                    failed = false,
                    poster = poster,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(800)

        val currentTranslation = boundsOf("当前翻译")
        val nextOriginal = boundsOf("下一句原文")
        val maxGapPx = with(composeRule.density) { 32.dp.toPx() }
        assertTrue(nextOriginal.top > currentTranslation.bottom)
        assertTrue(
            "gap=${nextOriginal.top - currentTranslation.bottom}",
            nextOriginal.top - currentTranslation.bottom <= maxGapPx,
        )

        val before = boundsOf("下一句原文").top
        composeRule.runOnIdle { positionMs.value = 2_100L }
        composeRule.mainClock.advanceTimeBy(900)
        val after = boundsOf("下一句原文").top

        assertTrue("before=$before after=$after", after < before)
    }

    private fun boundsOf(text: String): Rect =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private companion object {
        val testLyrics = SyncedLyrics(
            listOf(
                SyncedLine("当前原文", "当前翻译", 0, 1_000),
                SyncedLine("下一句原文", "下一句翻译", 1_000, 2_000),
                SyncedLine("第三句原文", "第三句翻译", 2_000, 3_000),
                SyncedLine("第四句原文", "第四句翻译", 3_000, 4_000),
            ),
        )
    }
}

@androidx.compose.runtime.Composable
private fun rememberTestState(initial: Long): MutableState<Long> = androidx.compose.runtime.remember {
    mutableStateOf(initial)
}
