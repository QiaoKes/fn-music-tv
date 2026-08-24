package com.fnmusic.tv.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorInstallTest {
    private lateinit var application: Application
    private lateinit var updateDirectory: File
    private lateinit var scopeJob: Job
    private var permissionGranted = false

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        updateDirectory = File(application.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        application.getSharedPreferences("device_update_preferences", 0).edit().clear().commit()
        scopeJob = Job()
    }

    @After fun tearDown() {
        scopeJob.cancel()
        updateDirectory.deleteRecursively()
    }

    @Test fun `permission grant launches installer and installer return cleans apk`() {
        val candidate = candidateApk()
        val installed = mutableListOf<File>()
        val coordinator = coordinator(candidate, UpdateApkInstaller { installed += it })

        makeUpdateAvailable(coordinator)
        coordinator.startDownload()
        assertTrue(coordinator.state.value is UpdateUiState.AwaitingInstallPermission)

        coordinator.openInstallPermissionSettings()
        permissionGranted = true
        coordinator.onResumeFromSystem()
        assertEquals(listOf(candidate), installed)
        assertTrue(coordinator.state.value is UpdateUiState.AwaitingSystemConfirmation)
        assertTrue(candidate.exists())

        coordinator.onResumeFromSystem()
        val error = coordinator.state.value as UpdateUiState.Error
        assertTrue(error.message.contains("取消或未完成"))
        assertFalse(candidate.exists())
    }

    @Test fun `permission denial cleans apk and remains retryable`() {
        val candidate = candidateApk()
        val coordinator = coordinator(candidate, UpdateApkInstaller { error("must not install") })

        makeUpdateAvailable(coordinator)
        coordinator.startDownload()
        coordinator.openInstallPermissionSettings()
        coordinator.onResumeFromSystem()

        val error = coordinator.state.value as UpdateUiState.Error
        assertEquals(MANIFEST, error.manifest)
        assertTrue(error.message.contains("未允许"))
        assertFalse(candidate.exists())
    }

    @Test fun `settings launch failure cleans apk`() = runBlocking {
        val candidate = candidateApk()
        val coordinator = coordinator(candidate, UpdateApkInstaller { error("must not install") })

        makeUpdateAvailable(coordinator)
        coordinator.startDownload()
        coordinator.openInstallPermissionSettings()
        assertTrue(coordinator.effects.first() is UpdateEffect.LaunchIntent)
        coordinator.handleIntentLaunchFailure()

        val error = coordinator.state.value as UpdateUiState.Error
        assertTrue(error.message.contains("系统设置页"))
        assertFalse(candidate.exists())
    }

    @Test fun `installer launch failure cleans apk`() {
        permissionGranted = true
        val candidate = candidateApk()
        val coordinator = coordinator(candidate, UpdateApkInstaller { throw IllegalStateException("no installer") })

        makeUpdateAvailable(coordinator)
        coordinator.startDownload()

        val error = coordinator.state.value as UpdateUiState.Error
        assertTrue(error.message.contains("系统安装程序"))
        assertFalse(candidate.exists())
    }

    private fun coordinator(candidate: File, installer: UpdateApkInstaller): UpdateCoordinator = UpdateCoordinator(
        application = application,
        scope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
        preferences = UpdatePreferences(application),
        client = UpdateManifestSource { MANIFEST },
        downloader = object : UpdateApkDownloader {
            override suspend fun download(manifest: UpdateManifest, onProgress: (Long) -> Unit): File {
                onProgress(candidate.length())
                return candidate
            }

            override fun cleanAll() = Unit
        },
        verifier = UpdateApkVerifier { file, _ -> file },
        installer = installer,
        canRequestPackageInstalls = { permissionGranted },
    )

    private fun makeUpdateAvailable(coordinator: UpdateCoordinator) {
        coordinator.checkManually()
        assertTrue(coordinator.state.value is UpdateUiState.Available)
    }

    private fun candidateApk(): File = File(updateDirectory, "candidate.apk").apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private companion object {
        val MANIFEST = UpdateManifest(
            versionName = "99.0.0",
            versionCode = Long.MAX_VALUE,
            title = "test",
            notes = "test",
            apkUrl = "https://updates.example.com/test.apk",
            apkSize = 3,
            apkSha256 = "0".repeat(64),
        )
    }
}
