package com.fnmusic.tv.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.TvMusicApplication
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun approvedVerticalLayoutFitsTvViewport() {
        val application = ApplicationProvider.getApplicationContext<TvMusicApplication>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val retainedState = remember(scope) { LibraryRetainedStateStore(scope) }
            FnMusicTheme {
                CompositionLocalProvider(LocalLibraryRetainedState provides retainedState) {
                    SettingsScreen(application.container)
                }
            }
        }

        listOf(
            "设置",
            "播放与歌词",
            "图片磁盘缓存上限",
            "32 MB",
            "64 MB",
            "128 MB",
            "256 MB",
            "关于",
            "回声台",
            "Tag mig hånden",
            "github.com/QiaoKes/fn-music-tv",
        ).forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        if (application.container.updateController.enabled) {
            compose.onNodeWithText("检查更新").assertIsDisplayed()
        }

        val screenshot = File(application.filesDir, "settings-screen-test.png")
        FileOutputStream(screenshot).use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue(screenshot.length() > 0L)
    }
}
