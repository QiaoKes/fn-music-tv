package com.fnmusic.tv.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fnmusic.tv.update.UpdateCheckSource
import com.fnmusic.tv.update.UpdateController
import com.fnmusic.tv.update.UpdateEffect
import com.fnmusic.tv.update.UpdateManifest
import com.fnmusic.tv.update.UpdateUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ignoredManualVersionShowsAllActionsAndUpdatesOnce() {
        val controller = FakeUpdateController()
        compose.setContent {
            FnMusicTheme {
                UpdateDialogHost(
                    UpdateUiState.Available(manifest(), ignored = true, source = UpdateCheckSource.Manual),
                    controller,
                )
            }
        }

        compose.onNodeWithText("此版本已忽略").assertExists()
        compose.onNodeWithText("立即更新").assertIsDisplayed().assertIsFocused().performClick()
        compose.onNodeWithText("稍后提醒").assertIsDisplayed()
        compose.onNodeWithText("忽略 1.0.6").assertIsDisplayed()
        assertEquals(1, controller.downloadCount)
    }

    @Test fun longReleaseNotesKeepEveryActionInsideTheTvViewport() {
        val controller = FakeUpdateController()
        val longNotes = """
            安装提示

            - 如果从旧版本覆盖安装时点击 APK 没有反应，请先卸载旧版本，再安装新版本。
            - 卸载会清除本地保存的登录信息与应用设置，安装后需要重新登录。

            新增

            - 重构歌词模块，在线歌词可同时保留原文、翻译与逐字时间。
            - 在线歌词优先选择带翻译的结果，其次选择可靠的逐字歌词。

            体验优化

            - 播放器改为多行平滑滚动歌词，提升远距离观看体验。
            - 调整歌词适配优先级并保留普通 LRC 安全回退。
        """.trimIndent()

        compose.setContent {
            FnMusicTheme {
                UpdateDialogHost(
                    UpdateUiState.Available(
                        manifest().copy(notes = longNotes),
                        ignored = false,
                        source = UpdateCheckSource.Automatic,
                    ),
                    controller,
                )
            }
        }

        compose.onNodeWithText("立即更新").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("稍后提醒").assertIsDisplayed()
        compose.onNodeWithText("忽略 1.0.6").assertIsDisplayed()
        compose.onNodeWithText("返回键关闭").assertIsDisplayed()
    }

    @Test fun downloadProgressHasPercentAndCancelAction() {
        val controller = FakeUpdateController()
        compose.setContent {
            FnMusicTheme {
                UpdateDialogHost(UpdateUiState.Downloading(manifest(), downloadedBytes = 512), controller)
            }
        }

        compose.onNodeWithText("50% · 0.5 KB / 1.0 KB").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, controller.cancelCount)
    }

    private fun manifest() = UpdateManifest(
        versionName = "1.0.6",
        versionCode = 22,
        title = "v1.0.6",
        notes = "修复播放问题",
        apkUrl = "https://download.example.com/releases/22/app.apk",
        apkSize = 1_024,
        apkSha256 = "a".repeat(64),
    )
}

private class FakeUpdateController : UpdateController {
    override val state: StateFlow<UpdateUiState> = MutableStateFlow(UpdateUiState.Idle)
    override val effects: Flow<UpdateEffect> = emptyFlow()
    override val enabled = true
    var downloadCount = 0
    var cancelCount = 0

    override fun checkManually() = Unit
    override fun ignoreAvailableVersion() = Unit
    override fun dismiss() = Unit
    override fun startDownload() { downloadCount += 1 }
    override fun cancelDownload() { cancelCount += 1 }
    override fun openInstallPermissionSettings() = Unit
    override fun setAutomaticPromptAllowed(allowed: Boolean) = Unit
}
