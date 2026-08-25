package com.fnmusic.tv.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface UpdateApkVerifier {
    suspend fun verify(file: File, manifest: UpdateManifest): File
}

internal class ApkVerifier(private val context: Context) : UpdateApkVerifier {
    override suspend fun verify(file: File, manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val candidate = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            signingInfoFlags(),
        ) ?: throw UpdateFailure("无法读取更新安装包")
        if (candidate.packageName != UPDATE_PACKAGE_NAME) throw UpdateFailure("更新安装包的应用标识不匹配")
        val candidateVersionCode = versionCode(candidate)
        if (candidateVersionCode != manifest.versionCode || candidateVersionCode <= installedVersionCode()) {
            throw UpdateFailure("更新安装包的版本号无效")
        }
        val installed = packageManager.getPackageInfo(context.packageName, signingInfoFlags())
        if (!signerSetsMatch(signerDigests(candidate), signerDigests(installed))) {
            throw UpdateFailure("更新安装包签名不匹配")
        }
        file
    }

    private fun installedVersionCode(): Long =
        versionCode(context.packageManager.getPackageInfo(context.packageName, 0))
}

internal fun signerSetsMatch(candidate: Set<String>, installed: Set<String>): Boolean =
    candidate.isNotEmpty() && installed.isNotEmpty() && candidate == installed

private fun signingInfoFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

private fun versionCode(packageInfo: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }

internal fun signerDigests(packageInfo: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures.orEmpty()
    }
    return signatures.mapTo(mutableSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
