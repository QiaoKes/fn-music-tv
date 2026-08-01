package com.fnmusic.tv.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.model.PlaybackCredentials
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.SlidingQueueReducer
import com.fnmusic.tv.core.model.playback.SlidingQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val audioFormat: String = "",
    val mediaId: String = "",
    val artworkUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val currentIndex: Int = 0,
    val itemCount: Int = 0,
    val error: String? = null,
    val queueError: String? = null,
    val canRetryQueue: Boolean = false,
)

class PlaybackController(
    private val context: Context,
    private val localStore: LocalStore,
    private val musicRepository: MusicRepository,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var pendingCredentials: PlaybackCredentials? = null
    private var restored = false
    private var lastSnapshotAt = 0L
    private var currentNamespace: String? = null
    private var frozenQueueJson: String? = null
    private var queueSource: QueueSource? = null
    private var queueWindow: SlidingQueueState? = null
    private var queuePageJob: Job? = null
    private var failedDirection: QueueDirection? = null
    private var queueError: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = project(player)
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess {
                    controller = it
                    it.addListener(listener)
                    pendingCredentials?.let(::configure)
                    project(it)
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun configure(credentials: PlaybackCredentials) {
        pendingCredentials = credentials
        if (currentNamespace != credentials.cacheNamespace) restored = false
        currentNamespace = credentials.cacheNamespace
        val current = controller ?: return
        val args = Bundle().apply {
            putString(PlaybackCommands.Token, credentials.rawAuthorization)
            putString(PlaybackCommands.CacheNamespace, credentials.cacheNamespace)
        }
        val configureFuture = current.sendCustomCommand(PlaybackCommands.ConfigureAuthCommand, args)
        configureFuture.addListener(
            {
                val configured = runCatching { configureFuture.get().resultCode == SessionResult.RESULT_SUCCESS }
                    .getOrDefault(false)
                if (configured && currentNamespace == credentials.cacheNamespace && !restored && current.mediaItemCount == 0) {
                    restored = true
                    scope.launch {
                        localStore.account(credentials.cacheNamespace)?.let { account ->
                            frozenQueueJson = account.frozenQueueJson
                            restoreQueue(current, account.queueJson)
                        }
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun playQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        source: QueueSource? = null,
        windowStart: Int = 0,
        firstPage: Int = 1,
        lastPage: Int = firstPage + ((tracks.size - 1).coerceAtLeast(0) / PAGE_SIZE),
        knownTotal: Int? = null,
    ) {
        val current = controller ?: return
        if (tracks.isEmpty()) return
        val selected = startIndex.coerceIn(tracks.indices)
        queuePageJob?.cancel()
        queueSource = source
        queueWindow = source?.let {
            SlidingQueueState(
                guids = tracks.map { track -> track.track.guid.value },
                currentIndex = selected,
                windowStart = windowStart,
                firstPage = firstPage,
                lastPage = lastPage,
                knownTotal = knownTotal,
                sort = it.sort,
            )
        }
        failedDirection = null
        queueError = null
        current.setMediaItems(tracks.map(::mediaItem), selected, 0L)
        current.prepare()
        if (autoPlay) current.play() else current.pause()
        snapshot(current, force = true)
    }

    fun enterRoam(track: PlaybackTrack) {
        val current = controller ?: return
        frozenQueueJson = encodeQueue(current)
        currentNamespace?.let { namespace -> scope.launch { localStore.saveFrozenQueue(namespace, frozenQueueJson) } }
        playQueue(listOf(track))
    }

    fun replaceRoamTrack(track: PlaybackTrack) = playQueue(listOf(track))

    fun exitRoam(): Boolean {
        val current = controller ?: return false
        val frozen = frozenQueueJson ?: run {
            clearSession()
            return false
        }
        val snapshot = decodeQueue(frozen)?.takeIf { it.items.isNotEmpty() }
        if (snapshot == null) {
            current.stop()
            current.clearMediaItems()
            frozenQueueJson = null
            currentNamespace?.let { namespace -> scope.launch { localStore.saveFrozenQueue(namespace, null) } }
            return false
        }
        current.setMediaItems(snapshot.items, snapshot.index.coerceIn(snapshot.items.indices), snapshot.positionMs)
        queueSource = snapshot.source
        queueWindow = snapshot.window
        current.prepare()
        current.pause()
        frozenQueueJson = null
        currentNamespace?.let { namespace -> scope.launch { localStore.saveFrozenQueue(namespace, null) } }
        return true
    }

    fun playPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekBy(offsetMs: Long) = controller?.let { player ->
        val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + offsetMs).coerceIn(0L, upperBound))
    }

    suspend fun clearMediaCache() {
        val current = controller ?: return
        suspendCancellableCoroutine { continuation ->
            val future = current.sendCustomCommand(PlaybackCommands.ClearCacheCommand, Bundle.EMPTY)
            future.addListener(
                {
                    runCatching { future.get() }
                    if (continuation.isActive) continuation.resume(Unit)
                },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }

    fun clearSession() {
        val namespace = currentNamespace
        controller?.run {
            stop()
            clearMediaItems()
            sendCustomCommand(PlaybackCommands.ClearAuthCommand, Bundle.EMPTY)
        }
        pendingCredentials = null
        restored = false
        currentNamespace = null
        frozenQueueJson = null
        queuePageJob?.cancel()
        queueSource = null
        queueWindow = null
        failedDirection = null
        queueError = null
        namespace?.let { scope.launch { localStore.clearNamespace(it, includeEssential = true) } }
        _state.value = PlaybackUiState(connected = controller != null)
    }

    fun disconnect() {
        controller?.let { snapshot(it, force = true) }
        ticker?.cancel()
        queuePageJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlaybackUiState()
    }

    private fun project(player: Player) {
        val metadata = player.mediaMetadata
        queueWindow = queueWindow?.copy(currentIndex = player.currentMediaItemIndex.coerceAtLeast(0))
        _state.value = PlaybackUiState(
            connected = true,
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            audioFormat = metadata.extras?.getString(AUDIO_FORMAT_KEY).orEmpty(),
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            artworkUrl = metadata.artworkUri?.toString(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            itemCount = player.mediaItemCount,
            error = player.playerError?.errorCodeName,
            queueError = queueError,
            canRetryQueue = failedDirection != null && queueWindow?.invalidated == false,
        )
        maybeLoadQueueEdge(player)
    }

    fun retryQueuePage() {
        val direction = failedDirection ?: return
        failedDirection = null
        queueError = null
        loadQueuePage(direction)
    }

    private fun maybeLoadQueueEdge(player: Player) {
        val window = queueWindow ?: return
        if (window.loading || window.invalidated || queueError != null || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        when {
            index <= PREFETCH_DISTANCE && !window.reachedStart -> loadQueuePage(QueueDirection.Previous)
            index >= player.mediaItemCount - 1 - PREFETCH_DISTANCE && !window.reachedEnd -> loadQueuePage(QueueDirection.Next)
        }
    }

    private fun loadQueuePage(direction: QueueDirection) {
        val source = queueSource ?: return
        val base = queueWindow ?: return
        if (queuePageJob?.isActive == true || base.loading || base.invalidated) return
        val loading = SlidingQueueReducer.loading(base)
        if (!loading.loading) return
        queueWindow = loading
        val page = if (direction == QueueDirection.Next) loading.lastPage + 1 else loading.firstPage - 1
        queuePageJob = scope.launch {
            var result = runCatching { musicRepository.queuePage(source, page) }
            RETRY_DELAYS.forEach { retryDelay ->
                if (result.isFailure) {
                    delay(retryDelay)
                    result = runCatching { musicRepository.queuePage(source, page) }
                }
            }
            result.mapCatching { loaded -> applyQueuePage(direction, loaded) }
                .onFailure {
                    queueWindow = queueWindow?.let(SlidingQueueReducer::failed)
                    failedDirection = direction
                    queueError = "队列分页加载失败"
                }
            queuePageJob = null
            controller?.let(::project)
        }
    }

    private fun applyQueuePage(direction: QueueDirection, page: com.fnmusic.tv.core.model.Page<com.fnmusic.tv.core.model.Track>) {
        val player = controller ?: return
        val state = queueWindow ?: return
        val prepared = musicRepository.prepareQueue(page.items)
        val guids = prepared.map { it.track.guid.value }
        val update = if (direction == QueueDirection.Next) {
            SlidingQueueReducer.append(state, page.page, guids, page.total, page.sort)
        } else {
            SlidingQueueReducer.prepend(state, page.page, guids, page.total, page.sort)
        }
        queueWindow = update.state
        if (update.state.invalidated) {
            failedDirection = null
            queueError = "歌单已更新，请重新载入"
            return
        }
        val items = prepared.map(::mediaItem)
        if (direction == QueueDirection.Next) {
            if (items.isNotEmpty()) player.addMediaItems(items)
            if (update.removeFromStart > 0) player.removeMediaItems(0, update.removeFromStart)
        } else {
            if (items.isNotEmpty()) player.addMediaItems(0, items)
            if (update.removeFromEnd > 0) {
                val from = (player.mediaItemCount - update.removeFromEnd).coerceAtLeast(0)
                player.removeMediaItems(from, player.mediaItemCount)
            }
        }
        queueWindow = queueWindow?.copy(currentIndex = player.currentMediaItemIndex.coerceAtLeast(0))
        failedDirection = null
        queueError = null
        snapshot(player, force = true)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let {
                    project(it)
                    snapshot(it)
                }
                delay(250)
            }
        }
    }

    private fun mediaItem(playback: PlaybackTrack): MediaItem = MediaItem.Builder()
        .setMediaId(playback.track.guid.value)
        .setUri(playback.streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playback.track.title)
                .setArtist(playback.track.artistName)
                .setAlbumTitle(playback.track.albumName)
                .setArtworkUri(playback.artworkUrl?.let(Uri::parse))
                .setExtras(audioFormatExtras(playback.track.audioFormat))
                .build(),
        )
        .build()

    private fun snapshot(player: Player, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSnapshotAt < 5_000) return
        lastSnapshotAt = now
        val namespace = currentNamespace ?: return
        val encoded = encodeQueue(player)
        scope.launch { localStore.saveQueue(namespace, encoded) }
    }

    private fun encodeQueue(player: Player): String {
        val items = JSONArray()
        repeat(player.mediaItemCount.coerceAtMost(250)) { index ->
            val item = player.getMediaItemAt(index)
            items.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("uri", item.localConfiguration?.uri?.toString())
                    .put("title", item.mediaMetadata.title?.toString())
                    .put("artist", item.mediaMetadata.artist?.toString())
                    .put("album", item.mediaMetadata.albumTitle?.toString())
                    .put("format", item.mediaMetadata.extras?.getString(AUDIO_FORMAT_KEY))
                    .put("art", item.mediaMetadata.artworkUri?.toString()),
            )
        }
        return JSONObject()
            .put("items", items)
            .put("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .put("position", player.currentPosition.coerceAtLeast(0))
            .also { root ->
                queueSource?.let { root.put("source", encodeSource(it)) }
                queueWindow?.let { window ->
                    root.put(
                        "window",
                        JSONObject()
                            .put("start", window.windowStart)
                            .put("firstPage", window.firstPage)
                            .put("lastPage", window.lastPage)
                            .put("total", window.knownTotal ?: JSONObject.NULL)
                            .put("sort", window.sort)
                            .put("reachedStart", window.reachedStart)
                            .put("reachedEnd", window.reachedEnd),
                    )
                }
            }
            .toString()
    }

    private fun restoreQueue(player: MediaController, encoded: String?) {
        decodeQueue(encoded ?: return)?.let { snapshot ->
            if (snapshot.items.isEmpty()) return
            player.setMediaItems(snapshot.items, snapshot.index.coerceIn(snapshot.items.indices), snapshot.positionMs)
            queueSource = snapshot.source
            queueWindow = snapshot.window
            player.prepare()
            player.pause()
        }
    }

    private fun decodeQueue(value: String): QueueSnapshot? = runCatching {
        val root = JSONObject(value)
        val array = root.getJSONArray("items")
        val items = buildList {
            repeat(array.length().coerceAtMost(250)) { index ->
                val item = array.getJSONObject(index)
                val uri = item.optString("uri")
                if (uri.isBlank()) return@repeat
                add(
                    MediaItem.Builder()
                        .setMediaId(item.optString("id"))
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.optString("title"))
                                .setArtist(item.optString("artist"))
                                .setAlbumTitle(item.optString("album"))
                                .setArtworkUri(item.optString("art").takeIf(String::isNotBlank)?.let(Uri::parse))
                                .setExtras(audioFormatExtras(item.optString("format")))
                                .build(),
                        ).build(),
                )
            }
        }
        val source = root.optJSONObject("source")?.let(::decodeSource)
        val window = root.optJSONObject("window")?.let { value ->
            source?.let {
                SlidingQueueState(
                    guids = items.map(MediaItem::mediaId),
                    currentIndex = root.optInt("index"),
                    windowStart = value.optInt("start"),
                    firstPage = value.optInt("firstPage", 1),
                    lastPage = value.optInt("lastPage", 1),
                    knownTotal = value.optInt("total").takeIf { !value.isNull("total") },
                    sort = value.optString("sort", it.sort),
                    reachedStart = value.optBoolean("reachedStart", value.optInt("firstPage", 1) <= 1),
                    reachedEnd = value.optBoolean("reachedEnd", false),
                )
            }
        }
        QueueSnapshot(items, root.optInt("index"), root.optLong("position"), source, window)
    }.getOrNull()

    private fun audioFormatExtras(audioFormat: String?): Bundle = Bundle().apply {
        audioFormat?.takeIf(String::isNotBlank)?.let { putString(AUDIO_FORMAT_KEY, it) }
    }

    private fun encodeSource(source: QueueSource): JSONObject = JSONObject()
        .put("kind", when (source) {
            is QueueSource.Playlist -> "playlist"
            is QueueSource.Artist -> "artist"
            is QueueSource.Album -> "album"
            is QueueSource.LibraryAllTracks -> "all"
        })
        .put("guid", when (source) {
            is QueueSource.Playlist -> source.guid
            is QueueSource.Artist -> source.guid
            is QueueSource.Album -> source.guid
            is QueueSource.LibraryAllTracks -> ""
        })
        .put("sort", source.sort)

    private fun decodeSource(value: JSONObject): QueueSource? {
        val sort = value.optString("sort")
        val guid = value.optString("guid")
        return when (value.optString("kind")) {
            "playlist" -> QueueSource.Playlist(guid, sort)
            "artist" -> QueueSource.Artist(guid, sort)
            "album" -> QueueSource.Album(guid, sort)
            "all" -> QueueSource.LibraryAllTracks(sort)
            else -> null
        }
    }

    private data class QueueSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val source: QueueSource?,
        val window: SlidingQueueState?,
    )

    private enum class QueueDirection { Previous, Next }

    private companion object {
        const val PAGE_SIZE = 50
        const val PREFETCH_DISTANCE = 15
        val RETRY_DELAYS = longArrayOf(500, 1_000, 2_000)
    }

}

private const val AUDIO_FORMAT_KEY = "com.fnmusic.tv.AUDIO_FORMAT"
