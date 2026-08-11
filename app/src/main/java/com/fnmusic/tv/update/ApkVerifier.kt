package com.fnmusic.tv.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ApkVerifier(private val context: Context) {
    suspend fun verify(file: File, manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val candidate = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw UpdateFailure("无法读取更新安装包")
        if (candidate.packageName != UPDATE_PACKAGE_NAME) throw UpdateFailure("更新安装包的应用标识不匹配")
        if (candidate.longVersionCode != manifest.versionCode || candidate.longVersionCode <= installedVersionCode()) {
            throw UpdateFailure("更新安装包的版本号无效")
        }
        val installed = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (!signerSetsMatch(signerDigests(candidate), signerDigests(installed))) {
            throw UpdateFailure("更新安装包签名不匹配")
        }
        file
    }

    private fun installedVersionCode(): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}

internal fun signerSetsMatch(candidate: Set<String>, installed: Set<String>): Boolean =
    candidate.isNotEmpty() && installed.isNotEmpty() && candidate == installed

internal fun signerDigests(packageInfo: PackageInfo): Set<String> {
    val signatures = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    return signatures.mapTo(mutableSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
