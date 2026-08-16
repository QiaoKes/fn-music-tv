package com.fnmusic.tv.update

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fnmusic.tv.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateInstallCapabilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager

    @Test fun installCapabilityMatchesDistributionFlavor() {
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestsInstallPackages = packageInfo.requestedPermissions
            ?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
        val receiver = ComponentName(context, UpdateInstallReceiver::class.java)

        if (BuildConfig.SELF_UPDATE_ENABLED) {
            assertTrue(requestsInstallPackages)
            assertFalse(packageManager.getReceiverInfo(receiver, 0).exported)
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
            assertNotNull(packageManager.resolveActivity(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY))
        } else {
            assertFalse(requestsInstallPackages)
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                packageManager.getReceiverInfo(receiver, 0)
            }
        }
    }
}
