package com.fnmusic.tv.update

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
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
        val legacyReceiver = ComponentName(context, "com.fnmusic.tv.update.UpdateInstallReceiver")
        val appUpdateService = ComponentName(context, "com.azhon.appupdate.service.DownloadService")
        val appUpdateDialog = ComponentName(context, "com.azhon.appupdate.view.UpdateDialogActivity")
        val provider = packageManager.resolveContentProvider(providerAuthority(context.packageName), PackageManager.GET_META_DATA)

        if (BuildConfig.SELF_UPDATE_ENABLED) {
            assertTrue(requestsInstallPackages)
            assertNotNull(provider)
            assertFalse(requireNotNull(provider).exported)
            val updateDirectory = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = java.io.File(updateDirectory, "provider-test.apk").apply { writeBytes(byteArrayOf(1)) }
            val uri = FileProvider.getUriForFile(context, providerAuthority(context.packageName), apk)
            assertTrue(uri.toString().contains("/verified_updates/"))
            apk.delete()
            val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                )
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            assertNotNull(packageManager.resolveActivity(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY))
        } else {
            assertFalse(requestsInstallPackages)
            assertTrue(provider == null)
        }
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            packageManager.getReceiverInfo(legacyReceiver, 0)
        }
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            packageManager.getServiceInfo(appUpdateService, 0)
        }
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            packageManager.getActivityInfo(appUpdateDialog, 0)
        }
    }
}
