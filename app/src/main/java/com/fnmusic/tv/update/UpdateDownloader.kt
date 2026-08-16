package com.fnmusic.tv.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

internal class UpdateDownloader(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val directory = File(context.cacheDir, "updates")

    suspend fun download(manifest: UpdateManifest, onProgress: (Long) -> Unit): File = coroutineScope {
        directory.mkdirs()
        val partial = File(directory, "${manifest.versionCode}.apk.part")
        val verified = File(directory, "${manifest.versionCode}.apk")
        partial.delete()
        verified.delete()
        val call = client.newCall(Request.Builder().url(manifest.apkUrl).build())
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw UpdateFailure("下载更新失败（${response.code}）")
                    if (response.request.url.host != validateHttpsUri(manifest.apkUrl, "APK 地址").host) {
                        throw UpdateFailure("APK 下载发生了跨域跳转")
                    }
                    val responseLength = response.body.contentLength()
                    if (responseLength >= 0 && responseLength != manifest.apkSize) {
                        throw UpdateFailure("更新文件大小不一致")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    response.body.byteStream().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > manifest.apkSize) throw UpdateFailure("更新文件大于预期")
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                            if (total != manifest.apkSize) throw UpdateFailure("更新文件不完整")
                        }
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualSha != manifest.apkSha256) throw UpdateFailure("更新文件校验失败")
                    if (!partial.renameTo(verified)) throw UpdateFailure("无法保存已校验的更新文件")
                    verified
                }
            }
        } catch (error: IOException) {
            partial.delete()
            verified.delete()
            coroutineContext.ensureActive()
            throw error
        } catch (error: Throwable) {
            partial.delete()
            verified.delete()
            throw error
        } finally {
            cancellationWatcher.cancel()
        }
    }

    fun cleanAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    fun cleanPartial() {
        directory.listFiles { file -> file.name.endsWith(".part") }?.forEach(File::delete)
    }
}
