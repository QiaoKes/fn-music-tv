package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiDecoderTest {
    @Test fun `maps authentication business error`() {
        val error = assertThrows(AppException::class.java) {
            ApiDecoder.decode<SystemConfigDto>("""{"code":99999,"msg":"INVALID TOKEN","data":null}""")
        }
        assertEquals(AppError.Unauthenticated, error.error)
    }

    @Test fun `decodes system config once at boundary`() {
        val dto = ApiDecoder.decode<SystemConfigDto>(
            """{"code":0,"msg":"success","data":{"serverGUID":"s","serverName":"NAS","serverVersion":"0.9.16","mediasrvVersion":"0.8.37"}}""",
        )
        assertEquals("NAS", dto.serverName)
    }

    @Test fun `accepts successful empty response for commands`() {
        ApiDecoder.decodeUnit("""{"code":0,"msg":"success","data":null}""")
    }

    @Test fun `decodes public transcode url field`() {
        val dto = ApiDecoder.decode<TranscodeResultDto>(
            """{"code":0,"data":{"status":"success","url":"/music/api/v1/track/hls/t/preset.m3u8"}}""",
        )
        assertEquals("/music/api/v1/track/hls/t/preset.m3u8", dto.url)
    }

    @Test fun `track metadata exposes normalized audio container`() {
        val dto = ApiDecoder.decode<TrackMetadataDto>(
            """{"code":0,"data":{"track":{"guid":"track-1","title":"Song","audioSpec":{"codec":"alac"}},"audioSpec":{"codec":"flac","container":"flac"}}}""",
        )

        assertEquals("FLAC", dto.toDomain().audioFormat)
    }

    @Test fun `track format falls back to codec when container is missing`() {
        val track = TrackDto(guid = "track-1", title = "Song", audioSpec = AudioSpecDto(codec = "aac"))

        assertEquals("AAC", track.toDomain().audioFormat)
    }
}
