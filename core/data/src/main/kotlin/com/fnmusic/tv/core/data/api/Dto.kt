package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.model.CollectionGuid
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.RoamNode
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.ServerGuid
import com.fnmusic.tv.core.model.ServerIdentity
import com.fnmusic.tv.core.model.SharedLibrary
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.User
import com.fnmusic.tv.core.model.UserGuid
import kotlinx.serialization.Serializable

@Serializable
data class SystemConfigDto(
    val serverGUID: String,
    val serverName: String,
    val serverVersion: String,
    val mediasrvVersion: String,
) {
    fun toDomain() = ServerIdentity(ServerGuid(serverGUID), serverName, serverVersion, mediasrvVersion)
}

@Serializable data class PasswordLoginRequest(val username: String, val password: String, val deviceId: String)
@Serializable data class LoginResultDto(val userToken: String, val user: UserDto)

@Serializable
data class UserDto(val guid: String, val name: String) {
    fun toDomain() = User(UserGuid(guid), name, null)
}

@Serializable
data class PlaylistDto(val guid: String, val name: String, val coverId: String? = null) {
    fun toDomain() = Playlist(CollectionGuid(guid), name, coverId)
}

@Serializable
data class PlaylistDetailDto(
    val guid: String,
    val name: String,
    val coverId: String? = null,
    val trackCount: Int = 0,
) {
    fun toDomain() = Playlist(CollectionGuid(guid), name, coverId, trackCount)
}

@Serializable
data class ArtistDto(
    val guid: String,
    val name: String,
    val coverId: String? = null,
    val trackCount: Int? = null,
    val albumCount: Int? = null,
) {
    fun toDomain() = Artist(CollectionGuid(guid), name, coverId, trackCount, albumCount)
}

@Serializable
data class AlbumDto(
    val guid: String,
    val name: String,
    val coverId: String? = null,
    val releaseDate: String? = null,
    val artists: List<ArtistDto> = emptyList(),
    val trackCount: Int? = null,
) {
    fun toDomain() = Album(
        CollectionGuid(guid),
        name,
        artists.joinToString(" / ") { it.name }.ifBlank { null },
        coverId,
        trackCount,
        releaseDate,
    )
}

@Serializable
data class AudioSpecDto(
    val codec: String = "",
    val container: String = "",
    val duration: Long = 0,
    val bitrate: Long = 0,
)

@Serializable
data class TrackDto(
    val guid: String,
    val title: String,
    val coverId: String? = null,
    val duration: Long = 0,
    val isCue: Boolean = false,
    val album: AlbumDto? = null,
    val artists: List<ArtistDto> = emptyList(),
    val audioSpec: AudioSpecDto = AudioSpecDto(),
    val accessStatus: Int? = null,
) {
    fun toDomain() = Track(
        TrackGuid(guid),
        title,
        artists.joinToString(" / ") { it.name }.ifBlank { null },
        album?.name,
        coverId,
        duration.takeIf { it > 0 },
        isCue,
        accessStatus,
    )
}

@Serializable data class SortedPageListDto<T>(val list: List<T> = emptyList(), val total: Int = list.size, val sort: String = "")

@Serializable
data class SharedLibraryDto(
    val guid: String,
    val name: String,
    val accessStatus: Int = -1,
    val updatedAt: Long = 0,
) {
    fun toDomain() = SharedLibrary(CollectionGuid(guid), name, accessStatus, updatedAt)
}

@Serializable data class ListDto<T>(val list: List<T> = emptyList())

@Serializable data class TrackMetadataDto(val track: TrackDto, val audioSpec: AudioSpecDto = AudioSpecDto())

@Serializable
data class LyricDto(
    val guid: String,
    val content: String,
    val isLRC: Boolean,
    val offset: Long = 0,
) {
    fun toDomain() = LyricDocument(guid, content, isLRC, offset)
}

@Serializable data class LyricListDto(val list: List<LyricDto> = emptyList(), val preferred: String? = null)

@Serializable
data class RoamNodeDto(val roamId: String, val track: TrackDto) {
    fun toDomain() = RoamNode(roamId, track.toDomain())
}

@Serializable data class RoamStartDto(val current: RoamNodeDto, val next: RoamNodeDto? = null)
@Serializable data class RoamWindowDto(val previous: RoamNodeDto? = null, val current: RoamNodeDto, val next: RoamNodeDto? = null) {
    fun toDomain() = RoamWindow(previous?.toDomain(), current.toDomain(), next?.toDomain())
}

@Serializable data class TempTokenRequest(val usage: String, val scopes: List<String>, val resourceGUID: String, val ttlSeconds: Int)
@Serializable data class TempTokenDto(val token: String, val expiredAt: Long)

@Serializable data class PageListDto<T>(val list: List<T> = emptyList(), val total: Int = list.size)

@Serializable
data class TranscodeResultDto(
    val status: String? = null,
    val url: String? = null,
)
