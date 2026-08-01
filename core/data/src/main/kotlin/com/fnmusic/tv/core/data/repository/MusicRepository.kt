package com.fnmusic.tv.core.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.data.api.AlbumDto
import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.api.ArtistDto
import com.fnmusic.tv.core.data.api.LyricListDto
import com.fnmusic.tv.core.data.api.PlaylistDetailDto
import com.fnmusic.tv.core.data.api.PlaylistDto
import com.fnmusic.tv.core.data.api.SharedLibraryDto
import com.fnmusic.tv.core.data.api.SortedPageListDto
import com.fnmusic.tv.core.data.api.TrackDto
import com.fnmusic.tv.core.data.local.CachedIndexEntity
import com.fnmusic.tv.core.data.local.CachedLyricEntity
import com.fnmusic.tv.core.data.local.CachedPageEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.SharedLibrary
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.lyric.LrcParser
import com.fnmusic.tv.core.model.lyric.LyricTimeline
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.preferences.CacheUsage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class MusicRepository(
    context: Context,
    private val session: SessionRepository,
    private val preferences: AppPreferences,
    private val localStore: LocalStore,
) {
    private val memoryArtwork = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val artworkDir = File(context.cacheDir, "artwork").apply { mkdirs() }
    private val mediaDir = File(context.cacheDir, "media")

    suspend fun playlists(): List<Playlist> = cachedIndex<List<PlaylistDto>, List<Playlist>>("playlists", {
        session.authenticated { it.playlists() }
    }) { list -> list.map { it.toDomain() } }

    suspend fun playlist(guid: String): Playlist = cachedIndex<PlaylistDetailDto, Playlist>("playlist:$guid", {
        session.authenticated { it.playlist(guid) }
    }) { it.toDomain() }

    suspend fun playlistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>("playlist:$guid", page, {
        session.authenticated { it.playlistTracks(guid, page) }
    }) { it.toDomain() }

    suspend fun artists(page: Int) = cachedPage<ArtistDto, Artist>("artists", page, {
        session.authenticated { it.artists(page) }
    }) { it.toDomain() }

    suspend fun artist(guid: String): Artist = cachedIndex<ArtistDto, Artist>("artist:$guid", {
        session.authenticated { it.artist(guid) }
    }) { it.toDomain() }

    suspend fun artistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>("artist-tracks:$guid", page, {
        session.authenticated { it.artistTracks(guid, page) }
    }) { it.toDomain() }

    suspend fun artistAlbums(guid: String, page: Int) = cachedPage<AlbumDto, Album>("artist-albums:$guid", page, {
        session.authenticated { it.artistAlbums(guid, page) }
    }) { it.toDomain() }

    suspend fun albums(page: Int) = cachedPage<AlbumDto, Album>("albums", page, {
        session.authenticated { it.albums(page) }
    }) { it.toDomain() }

    suspend fun album(guid: String): Album = cachedIndex<AlbumDto, Album>("album:$guid", {
        session.authenticated { it.album(guid) }
    }) { it.toDomain() }

    suspend fun albumTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>("album-tracks:$guid", page, {
        session.authenticated { it.albumTracks(guid, page) }
    }) { it.toDomain() }

    suspend fun allTracks(page: Int) = cachedPage<TrackDto, Track>("all-tracks", page, {
        session.authenticated { it.allTracks(page) }
    }) { it.toDomain() }

    suspend fun queuePage(source: QueueSource, page: Int): Page<Track> = when (source) {
        is QueueSource.Playlist -> playlistTracks(source.guid, page)
        is QueueSource.Artist -> artistTracks(source.guid, page)
        is QueueSource.Album -> albumTracks(source.guid, page)
        is QueueSource.LibraryAllTracks -> allTracks(page)
    }

    suspend fun sharedLibraries(): List<SharedLibrary> = cachedIndex<List<SharedLibraryDto>, List<SharedLibrary>>(
        "shared-libraries",
        { session.authenticated { it.sharedLibraries() } },
    ) { list -> list.map { it.toDomain() } }

    suspend fun prepare(track: Track): PlaybackTrack {
        if (track.accessStatus != null && track.accessStatus != 0) throw AppException(AppError.UnavailableTrack)
        val refreshed = session.authenticated { it.metadata(track.guid.value).track.toDomain() }
        if (refreshed.isCue) throw AppException(AppError.TranscodeUnavailable)
        val api = session.requireApi()
        return PlaybackTrack(
            refreshed,
            api.streamUrl(refreshed.guid.value).toString(),
            refreshed.coverId?.let { api.coverUrl(it, 800).toString() },
        )
    }

    fun prepareQueue(tracks: List<Track>): List<PlaybackTrack> {
        val api = session.requireApi()
        return tracks.asSequence()
            .filter { it.accessStatus == null || it.accessStatus == 0 }
            .filterNot { it.isCue }
            .take(250)
            .map { track ->
                PlaybackTrack(
                    track,
                    api.streamUrl(track.guid.value).toString(),
                    track.coverId?.let { api.coverUrl(it, 800).toString() },
                )
            }
            .toList()
    }

    suspend fun lyrics(trackGuid: String): Pair<LyricDocument?, LyricTimeline?> {
        val namespace = session.cacheNamespace()
        val cached = localStore.lyric(namespace, trackGuid)
        val response = try {
            session.authenticated { it.lyrics(trackGuid) }.also { result ->
                runCatching {
                    localStore.saveLyric(CachedLyricEntity(namespace, trackGuid, ApiDecoder.json.encodeToString(result), now()))
                }
            }
        } catch (cause: AppException) {
            if (cause.error != AppError.NetworkUnavailable || cached == null) throw cause
            runCatching { ApiDecoder.json.decodeFromString<LyricListDto>(cached.payload) }.getOrElse { throw cause }
        }
        val selected = response.list.firstOrNull { it.guid == response.preferred }
            ?: response.list.firstOrNull { it.isLRC }
            ?: response.list.firstOrNull()
        val document = selected?.toDomain()
        return document to document?.takeIf { it.isLrc }?.let { LrcParser.parse(it.content) }
    }

    suspend fun startRoam(): RoamWindow? = session.authenticated { it.roamStart(session.deviceId) }?.let {
        RoamWindow(null, it.current.toDomain(), it.next?.toDomain())
    }

    suspend fun nextRoam(roamId: String): RoamWindow = session.authenticated { it.roamNext(session.deviceId, roamId).toDomain() }
    suspend fun previousRoam(roamId: String): RoamWindow = session.authenticated { it.roamPrevious(session.deviceId, roamId).toDomain() }

    suspend fun artwork(coverId: String, variant: CoverVariant): ByteArray? {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return null
        val key = "${signedIn.server.guid.value}-${signedIn.user.guid.value}-$coverId-${variant.name}"
        memoryArtwork.get(key)?.takeIf(::isValidArtwork)?.let { return it }
        val file = File(artworkDir, key.sha256())
        val bytes = withContext(Dispatchers.IO) {
            file.takeIf(File::isFile)?.readBytes()?.takeIf(::isValidArtwork) ?: runCatching {
                var downloaded = session.authenticated { it.cover(coverId, variant.width) }
                if (!isValidArtwork(downloaded) && variant == CoverVariant.Poster) {
                    downloaded = session.authenticated { it.cover(coverId, CoverVariant.Player.width) }
                }
                if (!isValidArtwork(downloaded)) return@runCatching null
                downloaded.also {
                    file.writeBytes(downloaded)
                    pruneArtwork(preferences.state.value.cacheBudget.artworkBytes)
                }
            }.getOrNull()
        } ?: return null
        memoryArtwork.put(key, bytes)
        file.setLastModified(System.currentTimeMillis())
        return bytes
    }

    suspend fun clearArtwork() = withContext(Dispatchers.IO) {
        memoryArtwork.evictAll()
        artworkDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun clearLocalNamespace(includeEssential: Boolean) {
        runCatching { localStore.clearNamespace(session.cacheNamespace(), includeEssential) }
    }

    suspend fun cacheUsage(): CacheUsage = withContext(Dispatchers.IO) {
        CacheUsage(mediaDir.sizeRecursively(), artworkDir.sizeRecursively(), localStore.physicalBytes())
    }

    suspend fun applyArtworkBudget() = withContext(Dispatchers.IO) {
        pruneArtwork(preferences.state.value.cacheBudget.artworkBytes)
    }

    private fun pruneArtwork(limitBytes: Long) {
        var retained = 0L
        artworkDir.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val size = file.length()
                if (retained + size <= limitBytes) retained += size else file.delete()
            }
    }

    private fun File.sizeRecursively(): Long = if (!exists()) 0L else walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun isValidArtwork(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_DOWNLOAD_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..MAX_ARTWORK_EDGE &&
            bounds.outHeight in 1..MAX_ARTWORK_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_ARTWORK_PIXELS
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    private suspend inline fun <reified Dto, Domain> cachedPage(
        sourceKey: String,
        page: Int,
        crossinline fetch: suspend () -> SortedPageListDto<Dto>,
        noinline transform: (Dto) -> Domain,
    ): Page<Domain> {
        val namespace = session.cacheNamespace()
        val cached = localStore.page(namespace, sourceKey, page)
        val response = try {
            fetch().also { result ->
                runCatching {
                    localStore.savePage(
                        CachedPageEntity(
                            namespace,
                            sourceKey,
                            page,
                            ApiDecoder.json.encodeToString(result),
                            result.total,
                            result.sort,
                            now(),
                        ),
                    )
                }
            }
        } catch (cause: AppException) {
            if (cause.error != AppError.NetworkUnavailable || cached == null) throw cause
            runCatching { ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(cached.payload) }.getOrElse { throw cause }
        }
        return response.toPage(page, transform)
    }

    private suspend inline fun <reified Dto, Domain> cachedIndex(
        key: String,
        crossinline fetch: suspend () -> Dto,
        transform: (Dto) -> Domain,
    ): Domain {
        val namespace = session.cacheNamespace()
        val cached = localStore.index(namespace, key)
        val response = try {
            fetch().also { result ->
                runCatching {
                    localStore.saveIndex(CachedIndexEntity(namespace, key, ApiDecoder.json.encodeToString(result), now()))
                }
            }
        } catch (cause: AppException) {
            if (cause.error != AppError.NetworkUnavailable || cached == null) throw cause
            runCatching { ApiDecoder.json.decodeFromString<Dto>(cached.payload) }.getOrElse { throw cause }
        }
        return transform(response)
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun <Dto, Domain> com.fnmusic.tv.core.data.api.SortedPageListDto<Dto>.toPage(
        page: Int,
        transform: (Dto) -> Domain,
    ) = Page(list.map(transform), page, 50, total, sort)

    private companion object {
        const val MAX_ARTWORK_DOWNLOAD_BYTES = 20 * 1024 * 1024
        const val MAX_ARTWORK_EDGE = 8_192
        const val MAX_ARTWORK_PIXELS = 16_000_000L
    }
}
