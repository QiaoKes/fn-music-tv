package com.fnmusic.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startupAndLoginShell() = rule.collect(PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        exerciseLoginOrHome()
    }

    @Test fun homeCollectionAndPlayer() = rule.collect(PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        exerciseCollectionAndPlayer()
    }
}
