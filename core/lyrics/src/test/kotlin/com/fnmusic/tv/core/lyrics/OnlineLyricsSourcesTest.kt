package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsSourcesTest {
    private val request = LyricsMatchRequest("id", "Song", listOf("Artist"), "Album", 180_000)

    @Test fun `qq requests native qrc before lrc fallback`() = kotlinx.coroutines.runBlocking {
        val original = encryptQrc(
            """<Lyric_1 LyricType="1" LyricContent="[1000,1000]你(1000,500)好(1500,500)"/>""",
        )
        val translation = encryptQrc("[00:01.00]Hello")
        val http = FixtureHttp(
            getFixtures = mapOf(
                "client_search_cp" to searchFixture(),
            ),
            postFixtures = mapOf(
                "musicu.fcg" to """{"request":{"data":{"lyric":"$original","qrc_t":1,"lrc_t":0,"trans":"$translation","trans_t":1,"roma":""}}}""",
            ),
        )
        val source = QqMusicLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        val line = lyrics.lines.single() as KaraokeLine
        assertEquals("你好", line.lyricText())
        assertEquals("Hello", line.translation)
        assertEquals(1, http.postCalls)
    }

    @Test fun `qq falls back to ordinary lrc when native qrc fails`() = kotlinx.coroutines.runBlocking {
        val http = FixtureHttp(
            getFixtures = mapOf(
                "client_search_cp" to searchFixture(),
                "fcg_query_lyric_new" to """{"lyric":"[00:01.00]Hello","trans":"[00:01.00]你好"}""",
            ),
        )
        val source = QqMusicLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        assertEquals("Hello", lyrics.lines.single().lyricText())
        assertEquals("你好", lyrics.lines.single().translationText())
    }

    @Test fun `netease prefers yrc and aligns translation`() = kotlinx.coroutines.runBlocking {
        val http = FixtureHttp(
            getFixtures = mapOf(
                "search/get/web" to """{"result":{"songs":[{"id":2,"name":"Song","duration":180000,"artists":[{"name":"Artist"}],"album":{"name":"Album"}}]}}""",
                "song/lyric" to """{"yrc":{"lyric":"[1000,1000](1000,500,0)Hel(1500,500,0)lo"},"lrc":{"lyric":"[00:01.00]Hello"},"tlyric":{"lyric":"[00:01.00]你好"}}""",
            ),
        )
        val source = NeteaseLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        val line = lyrics.lines.single() as KaraokeLine
        assertEquals(1_000, line.syllables.first().start)
        assertEquals("你好", line.translation)
    }

    @Test fun `kugou decrypts native krc before parsing`() = kotlinx.coroutines.runBlocking {
        val content = encryptKrc("[0,1000]<0,500,0>Hel<500,500,0>lo")
        val http = FixtureHttp(
            getFixtures = mapOf(
                "song_search_v2" to """{"data":{"lists":[{"ID":"3","SongName":"Song","SingerName":"Artist","AlbumName":"Album","Duration":180,"FileHash":"hash"}]}}""",
                "lyrics.kugou.com/search" to """{"candidates":[{"id":"lyric","accesskey":"key","duration":180000,"score":60}]}""",
                "lyrics.kugou.com/download" to """{"contenttype":1,"content":"$content"}""",
            ),
        )
        val source = KugouLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        assertEquals("Hello", lyrics.lines.single().lyricText())
        assertTrue(lyrics.lines.single() is KaraokeLine)
    }

    private fun searchFixture() =
        """{"data":{"song":{"list":[{"songid":1,"songmid":"mid","songname":"Song","albumname":"Album","interval":180,"singer":[{"name":"Artist"}]}]}}}"""

    private class FixtureHttp(
        private val getFixtures: Map<String, String>,
        private val postFixtures: Map<String, String> = emptyMap(),
    ) : LyricsHttpClient {
        var postCalls = 0

        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): String =
            getFixtures.entries.firstOrNull { (key, _) -> url.contains(key) }?.value
                ?: error("No fixture for $url")

        override suspend fun post(url: String, body: String, headers: Map<String, String>): String {
            postCalls++
            return postFixtures.entries.firstOrNull { (key, _) -> url.contains(key) }?.value
                ?: error("No fixture for $url")
        }
    }

    private companion object {
        val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
        val krcKey = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )

        fun encryptQrc(value: String): String {
            val compressed = compress(value)
            val padded = compressed.copyOf(((compressed.size + 7) / 8) * 8)
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(qrcKey, "DESede"))
            return cipher.doFinal(padded).joinToString("") { "%02x".format(it) }
        }

        fun encryptKrc(value: String): String {
            val compressed = compress(value)
            val encrypted = ByteArray(compressed.size) { index ->
                (compressed[index].toInt() xor krcKey[index % krcKey.size].toInt()).toByte()
            }
            val payload = byteArrayOf('k'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte()) + encrypted
            return Base64.getEncoder().encodeToString(payload)
        }

        fun compress(value: String): ByteArray = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
    }
}
