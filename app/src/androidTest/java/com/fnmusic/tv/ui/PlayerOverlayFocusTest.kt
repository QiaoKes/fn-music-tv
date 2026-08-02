package com.fnmusic.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.fnmusic.tv.core.playback.PlaybackUiState
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.next
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerOverlayFocusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun queueFocusesCurrentRowAndKeepsFocusAfterSelection() {
        val selectedQueueIndex = AtomicInteger(-1)
        val items = listOf(
            PlaybackQueueItem("first", "First", "Artist A", queueIndex = 11, isCurrent = false),
            PlaybackQueueItem("current", "Current", "Artist B", queueIndex = 37, isCurrent = true),
            PlaybackQueueItem("last", "Last", "Artist C", queueIndex = 52, isCurrent = false),
        )
        composeRule.setContent {
            FnMusicTheme {
                PlaybackQueueOverlay(
                    items = items,
                    loadedCount = 9,
                    queueError = null,
                    canRetry = false,
                    onRetry = {},
                    onSelect = selectedQueueIndex::set,
                    onInteraction = {},
                )
            }
        }

        composeRule.onNodeWithText("当前播放 (9)").assertExists()
        val current = composeRule.onNodeWithContentDescription("2. Current Artist B，正在播放")
        current.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.runOnIdle { assertEquals(37, selectedQueueIndex.get()) }
        current.assertIsFocused()
    }

    @Test fun queuePreservesExistingFocusAndFallsBackWhenThatRowDisappears() {
        val queue = mutableStateOf(
            listOf(
                PlaybackQueueItem("first", "First", "Artist A", queueIndex = 11, isCurrent = false),
                PlaybackQueueItem("current", "Current", "Artist B", queueIndex = 37, isCurrent = true),
                PlaybackQueueItem("last", "Last", "Artist C", queueIndex = 52, isCurrent = false),
            ),
        )
        composeRule.setContent {
            FnMusicTheme {
                PlaybackQueueOverlay(
                    items = queue.value,
                    loadedCount = queue.value.size,
                    queueError = null,
                    canRetry = false,
                    onRetry = {},
                    onSelect = {},
                    onInteraction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("2. Current Artist B，正在播放")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("3. Last Artist C").assertIsFocused()

        composeRule.runOnIdle {
            queue.value = listOf(
                PlaybackQueueItem("current", "Current", "Artist B", queueIndex = 37, isCurrent = false),
                PlaybackQueueItem("last", "Last", "Artist C", queueIndex = 52, isCurrent = false),
                PlaybackQueueItem("new", "New", "Artist D", queueIndex = 61, isCurrent = true),
            )
        }
        composeRule.onNodeWithContentDescription("2. Last Artist C").assertIsFocused()

        composeRule.runOnIdle {
            queue.value = listOf(
                PlaybackQueueItem("current", "Current", "Artist B", queueIndex = 37, isCurrent = false),
                PlaybackQueueItem("new", "New", "Artist D", queueIndex = 61, isCurrent = true),
            )
        }
        val fallback = composeRule.onNodeWithContentDescription("2. New Artist D，正在播放")
        composeRule.waitUntil(timeoutMillis = 1_000) {
            runCatching { fallback.fetchSemanticsNode().config[SemanticsProperties.Focused] }.getOrDefault(false)
        }
        fallback.assertIsFocused()
    }

    @Test fun normalControlsTraverseAllActionsAndCycleEveryMode() {
        val modeCycles = AtomicInteger(0)
        renderControls(roaming = false, modeCycles = modeCycles)

        val play = composeRule.onNodeWithContentDescription("播放")
        val previous = composeRule.onNodeWithContentDescription("上一首")
        val next = composeRule.onNodeWithContentDescription("下一首")
        val queue = composeRule.onNodeWithContentDescription("播放队列，共 5 首")
        val listRepeat = composeRule.onNodeWithContentDescription("播放模式：列表循环")

        PlayMode.entries.forEach { mode ->
            composeRule.onNodeWithText(playModeLabel(mode)).assertDoesNotExist()
        }
        composeRule.onNodeWithText("队列 5").assertDoesNotExist()

        play.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        previous.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        listRepeat.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        previous.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        play.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        next.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        queue.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        next.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        play.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        previous.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }

        assertModeCycle("列表循环", "随机播放")
        assertModeCycle("随机播放", "单曲循环")
        assertModeCycle("单曲循环", "顺序播放")
        assertModeCycle("顺序播放", "列表循环")
        composeRule.runOnIdle { assertEquals(4, modeCycles.get()) }
    }

    @Test fun roamingControlsHideNormalActionsAndReachExit() {
        val exits = AtomicInteger(0)
        renderControls(roaming = true, exits = exits)

        PlayMode.entries.forEach { mode ->
            composeRule.onNodeWithContentDescription("播放模式：${playModeLabel(mode)}").assertDoesNotExist()
            composeRule.onNodeWithText(playModeLabel(mode)).assertDoesNotExist()
        }
        composeRule.onNodeWithContentDescription("播放队列，共 5 首").assertDoesNotExist()
        composeRule.onNodeWithText("队列 5").assertDoesNotExist()

        val play = composeRule.onNodeWithContentDescription("播放")
        val next = composeRule.onNodeWithContentDescription("下一首")
        val exit = composeRule.onNodeWithContentDescription("退出漫游")
        play.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        next.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        exit.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.runOnIdle { assertEquals(1, exits.get()) }
        exit.assertIsFocused()
    }

    @Test fun exitRoamLabelIsCenteredInsideItsButton() {
        renderControls(roaming = true)

        val button = composeRule.onNodeWithContentDescription("退出漫游").fetchSemanticsNode().boundsInRoot
        val label = composeRule.onNodeWithText("退出漫游", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue(abs(button.center.x - label.center.x) <= 1.5f)
        assertTrue(abs(button.center.y - label.center.y) <= 1.5f)
    }

    @Test fun nowPlayingPillKeepsCjkTitleInsideItsBounds() {
        val titleLayout = AtomicReference<TextLayoutResult>()
        composeRule.setContent {
            FnMusicTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NowPlayingPill(
                        playback = PlaybackUiState(
                            hasMedia = true,
                            isPlaying = true,
                            title = "39みゅーじっく！",
                            artist = "Mikito P",
                        ),
                        onClick = {},
                        onTitleTextLayout = titleLayout::set,
                    )
                }
            }
        }

        val pill = composeRule.onNodeWithContentDescription("当前播放：39みゅーじっく！")
            .fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithText("正在播放", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("39みゅーじっく！", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(status.top >= pill.top && status.bottom <= pill.bottom)
        assertTrue(title.top >= pill.top && title.bottom <= pill.bottom)
        assertTrue(pill.height in 84f..102f)
        assertTrue(pill.bottom - title.bottom >= 2f)
        composeRule.runOnIdle {
            val result = requireNotNull(titleLayout.get())
            assertEquals(1, result.lineCount)
            assertTrue(result.getLineTop(0) >= 0f)
            assertTrue(
                "lineBottom=${result.getLineBottom(0)}, textHeight=${result.size.height}, " +
                    "pillHeight=${pill.height}, statusHeight=${status.height}, titleHeight=${title.height}",
                result.getLineBottom(0) <= result.size.height.toFloat(),
            )
        }
        val screenshot = saveDisplayEvidence("now-playing-pill", pill)
        val lowestTitlePixel = lowestLightPixelY(screenshot, title)
        assertTrue(lowestTitlePixel != null)
        assertTrue(pill.bottom - requireNotNull(lowestTitlePixel) >= 6f)
    }

    @Test fun queueTrackTextGroupIsCenteredInsideItsRow() {
        composeRule.setContent {
            FnMusicTheme {
                PlaybackQueueOverlay(
                    items = listOf(
                        PlaybackQueueItem("current", "队列歌曲", "队列歌手", queueIndex = 0, isCurrent = true),
                    ),
                    loadedCount = 1,
                    queueError = null,
                    canRetry = false,
                    onRetry = {},
                    onSelect = {},
                    onInteraction = {},
                )
            }
        }

        val row = composeRule.onNodeWithContentDescription("1. 队列歌曲 队列歌手，正在播放")
            .fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("队列歌曲", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val artist = composeRule.onNodeWithText("队列歌手", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val textGroupCenterY = (title.top + artist.bottom) / 2f

        assertTrue(abs(row.center.y - textGroupCenterY) <= 2f)
    }

    @Test fun retryIsReachableFromTransportAndReturnsToIt() {
        val retries = AtomicInteger(0)
        val interactions = AtomicInteger(0)
        composeRule.setContent {
            FnMusicTheme {
                PlayerRetryHarness(
                    onRetry = { retries.incrementAndGet() },
                    onInteraction = { interactions.incrementAndGet() },
                )
            }
        }

        val play = composeRule.onNodeWithContentDescription("播放")
        val progress = composeRule.onNodeWithContentDescription("播放进度 0:12 / 3:00")
        val retry = composeRule.onNodeWithContentDescription("重试播放状态")
        play.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        progress.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        retry.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(1, retries.get())
            assertTrue(interactions.get() > 0)
        }
        retry.assertDoesNotExist()
        progress.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        play.assertIsFocused()
    }

    @Test fun modeClickResetsTheInteractionTimer() {
        val interactions = AtomicInteger(0)
        composeRule.setContent {
            FnMusicTheme {
                PlayerControlHarness(
                    roaming = false,
                    initialFocus = ControlInitialFocus.Mode,
                    onCycleMode = {},
                    onExitRoam = {},
                    onInteraction = { interactions.incrementAndGet() },
                )
            }
        }
        composeRule.onNodeWithContentDescription("播放模式：列表循环").assertIsFocused()
        composeRule.runOnIdle { interactions.set(0) }
        composeRule.onNodeWithContentDescription("播放模式：列表循环")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertTrue(interactions.get() > 0) }
    }

    @Test fun touchingProgressSeeksToTheTappedPosition() {
        val seekDelta = AtomicLong(Long.MIN_VALUE)
        composeRule.setContent {
            FnMusicTheme {
                PlayerControlHarness(
                    roaming = false,
                    onCycleMode = {},
                    onExitRoam = {},
                    onInteraction = {},
                    onSeek = seekDelta::set,
                )
            }
        }

        composeRule.onNodeWithContentDescription("播放进度 0:12 / 3:00")
            .performTouchInput { click() }

        composeRule.runOnIdle { assertTrue(seekDelta.get() in 77_000L..79_000L) }
    }

    @Test fun capturePlayerAndQueueEvidence() {
        val items = List(8) { index ->
            PlaybackQueueItem(
                mediaId = "track-$index",
                title = "测试歌曲 ${index + 1}",
                artist = "测试歌手 ${('A'.code + index).toChar()}",
                queueIndex = index,
                isCurrent = index == 2,
            )
        }
        val showQueue = mutableStateOf(false)
        composeRule.setContent {
            FnMusicTheme {
                if (showQueue.value) {
                    PlaybackQueueOverlay(
                        items = items,
                        loadedCount = items.size,
                        queueError = null,
                        canRetry = false,
                        onRetry = {},
                        onSelect = {},
                        onInteraction = {},
                    )
                } else {
                    PlayerControlHarness(
                        roaming = false,
                        onCycleMode = {},
                        onExitRoam = {},
                        onInteraction = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("播放")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("上一首").assertIsFocused()
        saveEvidence("player-controls")
        composeRule.runOnIdle { showQueue.value = true }
        saveEvidence("player-queue")
    }

    private fun assertModeCycle(from: String, to: String) {
        composeRule.onNodeWithContentDescription("播放模式：$from")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithContentDescription("播放模式：$to").assertIsFocused()
        composeRule.onNodeWithText(to).assertDoesNotExist()
    }

    private fun renderControls(
        roaming: Boolean,
        modeCycles: AtomicInteger = AtomicInteger(),
        exits: AtomicInteger = AtomicInteger(),
    ) {
        composeRule.setContent {
            FnMusicTheme {
                PlayerControlHarness(
                    roaming = roaming,
                    onCycleMode = { modeCycles.incrementAndGet() },
                    onExitRoam = { exits.incrementAndGet() },
                    onInteraction = {},
                )
            }
        }
    }

    private fun saveEvidence(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "evidence")
        assertTrue(directory.exists() || directory.mkdirs())
        FileOutputStream(File(directory, "$name-${bitmap.width}x${bitmap.height}.png")).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun saveDisplayEvidence(
        name: String,
        expectedDarkBounds: androidx.compose.ui.geometry.Rect,
    ): Bitmap {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var bitmap: Bitmap? = null
        for (attempt in 0 until 8) {
            Thread.sleep(150)
            bitmap = instrumentation.uiAutomation.takeScreenshot()
            val candidate = bitmap ?: continue
            val center = candidate.getPixel(
                expectedDarkBounds.center.x.toInt().coerceIn(0, candidate.width - 1),
                expectedDarkBounds.center.y.toInt().coerceIn(0, candidate.height - 1),
            )
            if (
                android.graphics.Color.red(center) < 100 &&
                android.graphics.Color.green(center) < 100 &&
                android.graphics.Color.blue(center) < 100
            ) {
                break
            }
            bitmap = null
        }
        val captured = requireNotNull(bitmap)
        assertTrue(captured.width > 0 && captured.height > 0)
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "evidence")
        assertTrue(directory.exists() || directory.mkdirs())
        FileOutputStream(File(directory, "$name-${captured.width}x${captured.height}.png")).use { output ->
            assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        return captured
    }
}

private fun lowestLightPixelY(bitmap: Bitmap, bounds: androidx.compose.ui.geometry.Rect): Int? {
    val left = floor(bounds.left).toInt().coerceIn(0, bitmap.width - 1)
    val right = ceil(bounds.right).toInt().coerceIn(left + 1, bitmap.width)
    val top = floor(bounds.top).toInt().coerceIn(0, bitmap.height - 1)
    val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, bitmap.height)
    for (y in bottom - 1 downTo top) {
        for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (
                android.graphics.Color.red(pixel) >= 180 &&
                android.graphics.Color.green(pixel) >= 180 &&
                android.graphics.Color.blue(pixel) >= 180
            ) {
                return y
            }
        }
    }
    return null
}

private enum class ControlInitialFocus { Play, Mode }

@Composable
private fun PlayerControlHarness(
    roaming: Boolean,
    initialFocus: ControlInitialFocus = ControlInitialFocus.Play,
    onCycleMode: () -> Unit,
    onExitRoam: () -> Unit,
    onInteraction: () -> Unit,
    onSeek: (Long) -> Unit = {},
) {
    val progressFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val exitRoamFocus = remember { FocusRequester() }
    val statusRetryFocus = remember { FocusRequester() }
    var playMode by remember { mutableStateOf(PlayMode.ListRepeat) }
    LaunchedEffect(Unit) {
        yield()
        if (initialFocus == ControlInitialFocus.Mode) modeFocus.requestFocus() else playFocus.requestFocus()
    }
    Box(Modifier.fillMaxSize()) {
        PlayerControlOverlay(
            positionMs = 12_000,
            durationMs = 180_000,
            isPlaying = false,
            roaming = roaming,
            previousEnabled = true,
            nextEnabled = true,
            progressFocus = progressFocus,
            previousFocus = previousFocus,
            playFocus = playFocus,
            nextFocus = nextFocus,
            modeFocus = modeFocus,
            queueFocus = queueFocus,
            exitRoamFocus = exitRoamFocus,
            statusRetryFocus = statusRetryFocus,
            statusRetryAvailable = false,
            playMode = playMode,
            queueCount = 5,
            onInteraction = onInteraction,
            onSeek = onSeek,
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onCyclePlayMode = {
                playMode = playMode.next()
                onCycleMode()
            },
            onOpenQueue = {},
            onExitRoam = onExitRoam,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlayerRetryHarness(
    onRetry: () -> Unit,
    onInteraction: () -> Unit,
) {
    val progressFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val exitRoamFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var retryVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        yield()
        playFocus.requestFocus()
    }
    Box(Modifier.fillMaxSize()) {
        if (retryVisible) {
            PlayerStatusRetryButton(
                focusRequester = retryFocus,
                returnFocusRequester = progressFocus,
                onInteraction = onInteraction,
                onRetry = {
                    retryVisible = false
                    onRetry()
                },
            )
        }
        PlayerControlOverlay(
            positionMs = 12_000,
            durationMs = 180_000,
            isPlaying = false,
            roaming = false,
            previousEnabled = true,
            nextEnabled = true,
            progressFocus = progressFocus,
            previousFocus = previousFocus,
            playFocus = playFocus,
            nextFocus = nextFocus,
            modeFocus = modeFocus,
            queueFocus = queueFocus,
            exitRoamFocus = exitRoamFocus,
            statusRetryFocus = retryFocus,
            statusRetryAvailable = true,
            playMode = PlayMode.ListRepeat,
            queueCount = 5,
            onInteraction = onInteraction,
            onSeek = {},
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onCyclePlayMode = {},
            onOpenQueue = {},
            onExitRoam = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
