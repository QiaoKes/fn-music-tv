package com.fnmusic.tv.update

import android.content.Context
import com.azhon.appupdate.util.ApkUtil
import java.io.File

internal fun interface UpdateApkInstaller {
    fun install(apk: File)
}

internal class UpdateInstaller(
    private val context: Context,
    private val installApk: (Context, String, File) -> Unit = ApkUtil::installApk,
) : UpdateApkInstaller {
    override fun install(apk: File) {
        installApk(context, providerAuthority(context.packageName), apk)
    }
}

internal fun providerAuthority(packageName: String): String = "$packageName.fileProvider"
