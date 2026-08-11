package com.fnmusic.tv.update

import android.content.Intent
import kotlinx.serialization.Serializable

internal const val UPDATE_PACKAGE_NAME = "com.fnmusic.tv"
internal const val MAX_UPDATE_MANIFEST_BYTES = 256 * 1024
internal const val MAX_UPDATE_NOTES_LENGTH = 4_000

@Serializable
internal data class UpdateManifestDto(
    val schemaVersion: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val apk: UpdateApkDto,
    val publishedAt: String,
    val githubReleaseUrl: String,
)

@Serializable
internal data class UpdateApkDto(
    val url: String,
    val size: Long,
    val sha256: String,
)

internal data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
)

internal enum class UpdateCheckSource { Automatic, Manual }

internal sealed interface UpdateUiState {
    data object Disabled : UpdateUiState
    data object Idle : UpdateUiState
    data class Checking(val source: UpdateCheckSource) : UpdateUiState
    data class UpToDate(val currentVersionName: String) : UpdateUiState
    data class Available(
        val manifest: UpdateManifest,
        val ignored: Boolean,
        val source: UpdateCheckSource,
    ) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val downloadedBytes: Long) : UpdateUiState
    data class Verifying(val manifest: UpdateManifest) : UpdateUiState
    data class AwaitingInstallPermission(val manifest: UpdateManifest) : UpdateUiState
    data class PreparingInstaller(val manifest: UpdateManifest) : UpdateUiState
    data class AwaitingSystemConfirmation(val manifest: UpdateManifest) : UpdateUiState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateUiState
}

internal sealed interface UpdateEffect {
    data class LaunchIntent(val intent: Intent) : UpdateEffect
}

internal interface UpdateController {
    val state: kotlinx.coroutines.flow.StateFlow<UpdateUiState>
    val effects: kotlinx.coroutines.flow.Flow<UpdateEffect>
    val enabled: Boolean

    fun checkManually()
    fun ignoreAvailableVersion()
    fun dismiss()
    fun startDownload()
    fun cancelDownload()
    fun openInstallPermissionSettings()
    fun setAutomaticPromptAllowed(allowed: Boolean)
}
