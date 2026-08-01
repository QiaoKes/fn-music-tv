package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class TrimMusicApi(
    private val server: NormalizedServer,
    private val client: OkHttpClient,
    private val token: () -> String?,
) {
    fun apiBase(): String = server.apiBase.toString()
    suspend fun systemConfig(): SystemConfigDto = get("sys/config", authenticated = false)

    suspend fun login(username: String, password: String, deviceId: String): LoginResultDto =
        post(
            "user/password-login",
            PasswordLoginRequest(username, password.sha256Hex(), deviceId),
            authenticated = false,
        )

    suspend fun me(): UserDto = get("user/me")

    suspend fun logout() {
        val builder = Request.Builder().url(server.resolveApi("user/logout"))
            .post(ByteArray(0).toRequestBody())
        executeUnit(builder, authenticated = true)
    }

    suspend fun playlists(): List<PlaylistDto> = get<PageListDto<PlaylistDto>>("playlist/list").list

    suspend fun playlist(guid: String): PlaylistDetailDto = get("playlist/detail", "guid" to guid)

    suspend fun playlistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/playlist-detail/list", "playlistGUID" to guid, "page" to page, "size" to size, "sort" to "trackAddedAt,desc")

    suspend fun artists(page: Int, size: Int = 50): SortedPageListDto<ArtistDto> =
        get("artist/list", "page" to page, "size" to size, "sort" to "trackCount,desc")

    suspend fun artist(guid: String): ArtistDto = get("artist/detail", "guid" to guid)

    suspend fun artistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun artistAlbums(guid: String, page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun albums(page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/list", "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun album(guid: String): AlbumDto = get("album/detail", "guid" to guid)

    suspend fun albumTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/album-detail/list", "albumGUID" to guid, "page" to page, "size" to size, "sort" to "trackNo,asc")

    suspend fun allTracks(page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/list", "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun sharedLibraries(): List<SharedLibraryDto> = get<ListDto<SharedLibraryDto>>("shared-library/list").list

    suspend fun metadata(guid: String): TrackMetadataDto = get("track/metadata", "guid" to guid)

    suspend fun lyrics(guid: String): LyricListDto = get("lyric/list", "trackGUID" to guid)

    suspend fun roamStart(deviceId: String): RoamStartDto? = getNullable("track/roam-start", "deviceId" to deviceId)

    suspend fun roamNext(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-next", "deviceId" to deviceId, "relativeRoamId" to roamId)

    suspend fun roamPrevious(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-previous", "deviceId" to deviceId, "relativeRoamId" to roamId)

    fun streamUrl(guid: String) = server.resolveApi("track/stream").newBuilder().addQueryParameter("guid", guid).build()

    fun coverUrl(coverId: String, size: Int?) = server.resolveApi("static/cover").newBuilder()
        .addQueryParameter("coverId", coverId)
        .apply { if (size != null) addQueryParameter("size", size.toString()) }
        .build()

    suspend fun cover(coverId: String, size: Int?): ByteArray = executeBytes(
        Request.Builder().url(coverUrl(coverId, size)),
        authenticated = true,
    )

    private suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T =
        execute(Request.Builder().url(server.resolveApi(path)), authenticated)

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, Any>): T {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return execute(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified T> getNullable(path: String, vararg query: Pair<String, Any>): T? {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return executeNullable(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        path: String,
        body: RequestType,
        authenticated: Boolean = true,
    ): ResponseType {
        val json = ApiDecoder.json.encodeToString(body)
        val builder = Request.Builder().url(server.resolveApi(path))
            .post(json.toRequestBody("application/json".toMediaType()))
        return execute(builder, authenticated)
    }

    private suspend inline fun <reified T> execute(builder: Request.Builder, authenticated: Boolean): T =
        withContext(Dispatchers.IO) {
            if (authenticated) {
                val current = token() ?: throw AppException(AppError.Unauthenticated)
                builder.header("Authorization", current)
            }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isRedirect) throw AppException(AppError.NetworkUnavailable)
                    if (response.code == 401) throw AppException(AppError.Unauthenticated)
                    if (response.code == 404) throw AppException(AppError.NotFound)
                    if (!response.isSuccessful) throw AppException(AppError.NetworkUnavailable)
                    ApiDecoder.decode<T>(response.body.string())
                }
            } catch (cause: AppException) {
                throw cause
            } catch (cause: IOException) {
                throw AppException(AppError.NetworkUnavailable, cause)
            }
        }

    private suspend inline fun <reified T> executeNullable(builder: Request.Builder, authenticated: Boolean): T? =
        withContext(Dispatchers.IO) {
            if (authenticated) builder.header("Authorization", token() ?: throw AppException(AppError.Unauthenticated))
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 401) throw AppException(AppError.Unauthenticated)
                    if (!response.isSuccessful || response.isRedirect) throw AppException(AppError.NetworkUnavailable)
                    ApiDecoder.decodeNullable<T>(response.body.string())
                }
            } catch (cause: AppException) {
                throw cause
            } catch (cause: IOException) {
                throw AppException(AppError.NetworkUnavailable, cause)
            }
        }

    private suspend fun executeBytes(builder: Request.Builder, authenticated: Boolean): ByteArray =
        withContext(Dispatchers.IO) {
            if (authenticated) builder.header("Authorization", token() ?: throw AppException(AppError.Unauthenticated))
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful || response.isRedirect || response.header("Content-Type")?.contains("json") == true) {
                        throw AppException(AppError.NetworkUnavailable)
                    }
                    response.body.bytes()
                }
            } catch (cause: AppException) {
                throw cause
            } catch (cause: IOException) {
                throw AppException(AppError.NetworkUnavailable, cause)
            }
        }

    private suspend fun executeUnit(builder: Request.Builder, authenticated: Boolean) = withContext(Dispatchers.IO) {
        if (authenticated) {
            val current = token() ?: throw AppException(AppError.Unauthenticated)
            builder.header("Authorization", current)
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.isRedirect) throw AppException(AppError.NetworkUnavailable)
                if (response.code == 401) throw AppException(AppError.Unauthenticated)
                if (response.code == 404) throw AppException(AppError.NotFound)
                if (!response.isSuccessful) throw AppException(AppError.NetworkUnavailable)
                ApiDecoder.decodeUnit(response.body.string())
            }
        } catch (cause: AppException) {
            throw cause
        } catch (cause: IOException) {
            throw AppException(AppError.NetworkUnavailable, cause)
        }
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}
