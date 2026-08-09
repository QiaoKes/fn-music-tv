// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.lyrics

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface LyricsHttpClient {
    suspend fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String = throw LyricsTransportException("Lyrics HTTP client does not support POST")
}

class OkHttpLyricsHttpClient(
    private val client: OkHttpClient,
) : LyricsHttpClient {
    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): String {
        val requestUrl = url.toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(requestUrl)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach(::header) }
            .build()
        return execute(request)
    }

    override suspend fun post(url: String, body: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach(::header) }
            .post(body.toRequestBody())
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(LyricsTransportException("Lyrics request failed", e))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) throw LyricsTransportException("Lyrics HTTP ${it.code}")
                                it.body.string()
                            }
                        }
                        if (!continuation.isActive) return
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }

    private companion object {
        const val USER_AGENT = "FnMusicTV/0.1 (https://github.com/QiaoKes/fn-music-tv)"
    }
}
