package com.fnmusic.tv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "com.fnmusic.tv"
private const val WAIT_MS = 5_000L

internal fun MacrobenchmarkScope.exerciseLoginOrHome() {
    device.wait(Until.hasObject(By.text("回声台")), WAIT_MS)
    if (device.hasObject(By.text("NAS 地址"))) {
        device.pressDPadDown()
        device.pressDPadDown()
        device.pressDPadUp()
        return
    }
    device.wait(Until.hasObject(By.text("首页")), WAIT_MS)
    device.findObject(By.text("首页"))?.click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.exerciseCollectionAndPlayer() {
    exerciseLoginOrHome()
    if (device.hasObject(By.text("NAS 地址"))) return

    device.findObject(By.text("全部歌单"))?.click()
    device.wait(Until.hasObject(By.text("全部歌单")), WAIT_MS)
    device.pressDPadDown()
    device.pressEnter()
    device.waitForIdle()
    device.pressDPadRight()
    device.pressDPadDown()
    device.pressEnter()
    device.wait(Until.hasObject(By.text("播放")), WAIT_MS)
}
