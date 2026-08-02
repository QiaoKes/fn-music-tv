package com.fnmusic.tv.core.playback

import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.QueueSource

data class StoredPlaybackSession(
    val queueJson: String?,
    val frozenQueueJson: String?,
)

interface PlaybackSessionStore {
    suspend fun read(namespace: String): StoredPlaybackSession?
    suspend fun save(namespace: String, snapshotJson: String?)
    suspend fun clear(namespace: String)
}

interface PlaybackContentSource {
    suspend fun queuePage(source: QueueSource, page: Int): Page<Track>
    suspend fun prepare(track: Track): PlaybackTrack
    fun prepareQueue(tracks: List<Track>): List<PlaybackTrack>
    suspend fun startRoam(): RoamWindow?
    suspend fun nextRoam(roamId: String): RoamWindow
    suspend fun previousRoam(roamId: String): RoamWindow
}
