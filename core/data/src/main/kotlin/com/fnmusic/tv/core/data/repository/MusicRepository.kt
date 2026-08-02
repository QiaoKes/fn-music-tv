package com.fnmusic.tv.core.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import com.fnmusic.tv.core.data.api.AlbumDto
import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.api.ArtistDto
import com.fnmusic.tv.core.data.api.LyricListDto
import com.fnmusic.tv.core.data.api.PlaylistDetailDto
import com.fnmusic.tv.core.data.api.PlaylistDto
import com.fnmusic.tv.core.data.api.SharedLibraryDto
import com.fnmusic.tv.core.data.api.SortedPageListDto
import com.fnmusic.tv.core.data.api.TrackDto
import com.fnmusic.tv.core.data.api.TrackMetadataDto
import com.fnmusic.tv.core.data.api.isRetryableRequestFailure
import com.fnmusic.tv.core.data.local.CachedIndexEntity
import com.fnmusic.tv.core.data.local.CachedLyricEntity
import com.fnmusic.tv.core.data.local.CachedPageEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.data.preferences.AppPreferences
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
import com.fnmusic.tv.core.model.lyric.LyricParser
import com.fnmusic.tv.core.model.lyric.LyricTimeline
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.preferences.CacheUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val MAX_ARTWORK_DOWNLOAD_BYTES = 20 * 1024 * 1024
private const val MAX_ARTWORK_EDGE = 8_192
private const val MAX_ARTWORK_PIXELS = 16_000_000L
private const val ARTWORK_VALIDATION_LONG_EDGE = 128

internal data class ArtworkBounds(val width: Int, val height: Int)

internal fun decodeLyrics(response: LyricListDto): Pair<LyricDocument?, LyricTimeline?> {
    val selected = response.list.firstOrNull { it.guid == response.preferred }
        ?: response.list.firstOrNull { it.isLRC }
        ?: response.list.firstOrNull()
    val document = selected?.toDomain()
    val timeline = document?.let { LyricParser.parse(it.content) }?.takeIf { it.lines.isNotEmpty() }
    return document to timeline
}

internal fun isValidArtworkBytes(bytes: ByteArray): Boolean = isValidArtworkBytes(
    bytes = bytes,
    readBounds = { encoded ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        ArtworkBounds(options.outWidth, options.outHeight)
    },
    decodeSampled = { encoded, sample ->
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.let { bitmap ->
            bitmap.recycle()
            true
        } ?: false
    },
)

internal fun isValidArtworkBytes(
    bytes: ByteArray,
    readBounds: (ByteArray) -> ArtworkBounds?,
    decodeSampled: (ByteArray, Int) -> Boolean,
): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_DOWNLOAD_BYTES) return false
    val bounds = runCatching { readBounds(bytes) }.getOrNull() ?: return false
    if (
        bounds.width !in 1..MAX_ARTWORK_EDGE ||
        bounds.height !in 1..MAX_ARTWORK_EDGE ||
        bounds.width.toLong() * bounds.height > MAX_ARTWORK_PIXELS
    ) {
        return false
    }

    var sample = 1
    while (
        bounds.width / sample > ARTWORK_VALIDATION_LONG_EDGE ||
        bounds.height / sample > ARTWORK_VALIDATION_LONG_EDGE
    ) {
        sample *= 2
    }
    return runCatching { decodeSampled(bytes, sample) }.getOrDefault(false)
}

class MusicRepository internal constructor(
    context: Context,
    private val session: SessionRepository,
    private val preferences: AppPreferences,
    private val localStore: LocalStore,
    metadataCapacityBytes: Int,
    repositoryScope: CoroutineScope,
) {
    constructor(
        context: Context,
        session: SessionRepository,
        preferences: AppPreferences,
        localStore: LocalStore,
    ) : this(
        context = context,
        session = session,
        preferences = preferences,
        localStore = localStore,
        metadataCapacityBytes = METADATA_CAPACITY_BYTES,
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val responses = SerializedResponseCache(metadataCapacityBytes, repositoryScope)
    private val artworkCache = ArtworkCache(
        root = context.cacheDir.resolve("artwork"),
        memoryCapacityBytes = ARTWORK_MEMORY_CAPACITY_BYTES,
        diskBudgetBytes = { preferences.state.value.cacheBudget.artworkBytes },
        isValid = ::isValidArtworkBytes,
        scope = repositoryScope,
    )

    init {
        repositoryScope.launch { artworkCache.initialize() }
    }

    suspend fun playlists(): List<Playlist> = cachedIndex<List<PlaylistDto>, List<Playlist>>(
        key = "playlists",
        fetch = { session.authenticated { it.playlists() } },
    ) { list -> list.map(PlaylistDto::toDomain) }

    suspend fun playlist(guid: String): Playlist = cachedIndex<PlaylistDetailDto, Playlist>(
        key = "playlist:$guid",
        fetch = { session.authenticated { it.playlist(guid) } },
    ) { it.toDomain() }

    suspend fun playlistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "playlist:$guid",
        page = page,
        fetch = { session.authenticated { it.playlistTracks(guid, page) } },
    ) { it.toDomain() }

    suspend fun artists(page: Int) = cachedPage<ArtistDto, Artist>(
        sourceKey = "artists",
        page = page,
        fetch = { session.authenticated { it.artists(page) } },
    ) { it.toDomain() }

    suspend fun artist(guid: String): Artist = cachedIndex<ArtistDto, Artist>(
        key = "artist:$guid",
        fetch = { session.authenticated { it.artist(guid) } },
    ) { it.toDomain() }

    suspend fun artistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "artist-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.artistTracks(guid, page) } },
    ) { it.toDomain() }

    suspend fun artistAlbums(guid: String, page: Int) = cachedPage<AlbumDto, Album>(
        sourceKey = "artist-albums:$guid",
        page = page,
        fetch = { session.authenticated { it.artistAlbums(guid, page) } },
    ) { it.toDomain() }

    suspend fun albums(page: Int) = cachedPage<AlbumDto, Album>(
        sourceKey = "albums",
        page = page,
        fetch = { session.authenticated { it.albums(page) } },
    ) { it.toDomain() }

    suspend fun album(guid: String): Album = cachedIndex<AlbumDto, Album>(
        key = "album:$guid",
        fetch = { session.authenticated { it.album(guid) } },
    ) { it.toDomain() }

    suspend fun albumTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "album-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.albumTracks(guid, page) } },
    ) { it.toDomain() }

    suspend fun allTracks(page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "all-tracks",
        page = page,
        fetch = { session.authenticated { it.allTracks(page) } },
    ) { it.toDomain() }

    suspend fun queuePage(source: QueueSource, page: Int): Page<Track> = when (source) {
        is QueueSource.Playlist -> playlistTracks(source.guid, page)
        is QueueSource.Artist -> artistTracks(source.guid, page)
        is QueueSource.Album -> albumTracks(source.guid, page)
        is QueueSource.LibraryAllTracks -> allTracks(page)
    }

    suspend fun sharedLibraries(): List<SharedLibrary> = cachedIndex<List<SharedLibraryDto>, List<SharedLibrary>>(
        key = "shared-libraries",
        fetch = { session.authenticated { it.sharedLibraries() } },
    ) { list -> list.map(SharedLibraryDto::toDomain) }

    suspend fun trackMetadata(trackGuid: String): Track = cachedIndex<TrackMetadataDto, Track>(
        key = "track-metadata:$trackGuid",
        fetch = { session.authenticated { it.metadata(trackGuid) } },
    ) { it.toDomain() }

    suspend fun currentTrackMetadata(trackGuid: String): CurrentResourceResult<Track> = currentResource {
        withCurrentResourceRetry { trackMetadata(trackGuid) }
    }

    suspend fun prepare(track: Track): PlaybackTrack {
        if (track.accessStatus != null && track.accessStatus != 0) throw AppException(AppError.UnavailableTrack)
        val refreshed = trackMetadata(track.guid.value)
        if (refreshed.isCue) throw AppException(AppError.TranscodeUnavailable)
        val api = session.requireApi()
        return PlaybackTrack(
            refreshed,
            api.streamUrl(refreshed.guid.value).toString(),
            refreshed.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
        )
    }

    fun prepareQueue(tracks: List<Track>): List<PlaybackTrack> {
        val api = session.requireApi()
        return tracks.asSequence()
            .filter { it.accessStatus == null || it.accessStatus == 0 }
            .filterNot(Track::isCue)
            .take(250)
            .map { track ->
                PlaybackTrack(
                    track,
                    api.streamUrl(track.guid.value).toString(),
                    track.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
                )
            }
            .toList()
    }

    suspend fun lyrics(trackGuid: String): Pair<LyricDocument?, LyricTimeline?> =
        decodeLyrics(lyricResponse(trackGuid))

    suspend fun currentLyrics(trackGuid: String): CurrentResourceResult<CurrentLyrics> = currentResource {
        val (document, timeline) = withCurrentResourceRetry { lyrics(trackGuid) }
        document?.takeIf { it.content.isNotBlank() }?.let { CurrentLyrics(it, timeline) }
    }

    suspend fun startRoam(): RoamWindow? = session.authenticated { it.roamStart(session.deviceId) }?.let {
        RoamWindow(null, it.current.toDomain(), it.next?.toDomain())
    }

    suspend fun nextRoam(roamId: String): RoamWindow =
        session.authenticated { it.roamNext(session.deviceId, roamId).toDomain() }

    suspend fun previousRoam(roamId: String): RoamWindow =
        session.authenticated { it.roamPrevious(session.deviceId, roamId).toDomain() }

    suspend fun artwork(coverId: String, variant: CoverVariant): ByteArray? = try {
        loadArtwork(coverId, variant)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: AppException) {
        null
    }

    suspend fun currentArtwork(
        coverId: String,
        variant: CoverVariant,
    ): CurrentResourceResult<ByteArray> = currentResource {
        withCurrentResourceRetry { loadArtwork(coverId, variant) }
    }

    suspend fun invalidateNamespace(namespace: String, includeEssential: Boolean = false) {
        responses.invalidateNamespace(namespace)
        artworkCache.clearNamespace(namespace)
        localStore.clearNamespace(namespace, includeEssential)
    }

    suspend fun clearLocalNamespace(includeEssential: Boolean) {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return
        invalidateNamespace(namespace, includeEssential)
    }

    suspend fun clearArtwork() = artworkCache.clearAll()

    suspend fun clearAllEvictableCaches() {
        responses.invalidateAll()
        artworkCache.clearAll()
        localStore.clearAllEvictable()
    }

    suspend fun cacheUsage(): CacheUsage = CacheUsage(
        artworkBytes = artworkCache.usageBytes(),
        indexBytes = localStore.physicalBytes(),
    )

    suspend fun applyArtworkBudget() = artworkCache.applyBudget()

    private suspend fun loadArtwork(coverId: String, variant: CoverVariant): ByteArray? {
        val namespace = session.cacheNamespace()
        return artworkCache.get(namespace, coverId, variant) {
            session.authenticated { it.cover(coverId, variant.width) }
        }
    }

    private suspend fun lyricResponse(trackGuid: String): LyricListDto {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "lyric", trackGuid)
        var decodedResponse: LyricListDto? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    localStore.saveLyric(CachedLyricEntity(namespace, trackGuid, encoded, now()))
                }
            },
        ) {
            try {
                session.authenticated { it.lyrics(trackGuid) }
                    .also { decodedResponse = it }
                    .let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.lyric(namespace, trackGuid) } ?: throw cause
                decodedResponse = validatePayload<LyricListDto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return decodedResponse ?: ApiDecoder.json.decodeFromString(payload)
    }

    private suspend inline fun <reified Dto, Domain> cachedPage(
        sourceKey: String,
        page: Int,
        crossinline fetch: suspend () -> SortedPageListDto<Dto>,
        noinline transform: (Dto) -> Domain,
    ): Page<Domain> {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "page", sourceKey, page)
        var decodedResponse: SortedPageListDto<Dto>? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    val response = decodedResponse
                        ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(encoded)
                    localStore.savePage(
                        CachedPageEntity(
                            namespace = namespace,
                            sourceKey = sourceKey,
                            page = page,
                            payload = encoded,
                            total = response.total,
                            sort = response.sort,
                            accessedAt = now(),
                        ),
                    )
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.page(namespace, sourceKey, page) } ?: throw cause
                decodedResponse = validatePayload<SortedPageListDto<Dto>>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return (decodedResponse ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(payload))
            .toPage(page, transform)
    }

    private suspend inline fun <reified Dto, Domain> cachedIndex(
        key: String,
        crossinline fetch: suspend () -> Dto,
        transform: (Dto) -> Domain,
    ): Domain {
        val namespace = session.cacheNamespace()
        val cacheKey = ResponseCacheKey(namespace, "index", key)
        var decodedResponse: Dto? = null
        val payload = responses.getOrFetch(
            key = cacheKey,
            persist = { encoded ->
                bestEffort {
                    localStore.saveIndex(CachedIndexEntity(namespace, key, encoded, now()))
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.index(namespace, key) } ?: throw cause
                decodedResponse = validatePayload<Dto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return transform(decodedResponse ?: ApiDecoder.json.decodeFromString(payload))
    }

    private suspend fun <T> currentResource(block: suspend () -> T?): CurrentResourceResult<T> = try {
        block()?.let { CurrentResourceResult.Ready(it) } ?: CurrentResourceResult.Absent
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: AppException) {
        when (cause.error) {
            AppError.NotFound, AppError.Empty -> CurrentResourceResult.Absent
            else -> CurrentResourceResult.Failure(cause.error, cause.isRetryableRequestFailure)
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Room remains a fallback tier; a disk write failure must not fail a valid network response.
        }
    }

    private suspend fun <T> fallback(block: suspend () -> T): T? = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> validatePayload(payload: String): T? = try {
        ApiDecoder.json.decodeFromString(payload)
    } catch (_: Exception) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun <Dto, Domain> SortedPageListDto<Dto>.toPage(
        page: Int,
        transform: (Dto) -> Domain,
    ) = Page(list.map(transform), page, PAGE_SIZE, total, sort)

    private companion object {
        const val METADATA_CAPACITY_BYTES = 8 * 1024 * 1024
        const val ARTWORK_MEMORY_CAPACITY_BYTES = 24 * 1024 * 1024
        const val PAGE_SIZE = 50
    }
}
