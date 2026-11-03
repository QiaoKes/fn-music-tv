package com.fnmusic.tv.core.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStreamDiagnosisTest {

    @Test
    fun `parse errors qualify for stream probing`() {
        assertTrue(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED in playbackProbeErrorCodes)
        assertTrue(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED in playbackProbeErrorCodes)
        assertFalse(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS in playbackProbeErrorCodes)
        assertFalse(PlaybackException.ERROR_CODE_IO_UNSPECIFIED in playbackProbeErrorCodes)
    }

    @Test
    fun `json body is classified as server error payload`() {
        val probe = PlaybackStreamProbeResult(
            opened = true,
            status = null,
            headers = mapOf("Content-Type" to listOf("application/json")),
            observation = "",
            bytes = """{"code":1}""".toByteArray(),
        )

        val diagnosis = buildStreamDiagnosis(probe, relayMode = false, dumpPath = null)

        assertTrue(diagnosis.summary.contains("HTTP 2xx"))
        assertTrue(diagnosis.summary.contains("type=application/json"))
        assertTrue(diagnosis.summary.contains("疑似 JSON 错误体"))
        assertTrue(diagnosis.summary.contains("7B"))
    }

    @Test
    fun `failed open reports http status and observation`() {
        val probe = PlaybackStreamProbeResult(
            opened = false,
            status = 500,
            headers = emptyMap(),
            observation = "HTTP 500",
            bytes = ByteArray(0),
        )

        val diagnosis = buildStreamDiagnosis(probe, relayMode = true, dumpPath = null)

        assertTrue(diagnosis.summary.contains("HTTP 500"))
        assertTrue(diagnosis.summary.contains("探测异常=HTTP 500"))
        assertTrue(diagnosis.summary.contains("relay=true"))
        assertTrue(diagnosis.summary.contains("响应体为空(0 字节)"))
    }

    @Test
    fun `mp3 with valid id3 header reports declared tag length`() {
        val bytes = "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3, 0, 0) + // version, revision, flags
            byteArrayOf(0, 0, 0, 32) + // sync-safe tag size at indices 6..9
            ByteArray(64) { index -> (index + 1).toByte() }

        val note = classifyStreamBytes(bytes)

        assertTrue(note.contains("MP3"))
        assertTrue(note.contains("声明长度 32 字节"))
        assertTrue(note.contains("捕捉"))
    }
}
