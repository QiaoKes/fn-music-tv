package com.fnmusic.tv.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackServiceConfigurationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `playback uses direct authenticated HTTP data sources`() {
        val dataSource = createPlaybackHttpDataSourceFactory().createDataSource()

        assertEquals(DefaultHttpDataSource::class.java, dataSource.javaClass)
    }

    @Test
    fun `playback request headers include access code and relay cookies`() {
        val headers = playbackRequestHeaders("token", "encoded-code", relayMode = true)

        assertEquals("token", headers["Authorization"])
        assertEquals("music-token=token; mode=relay", headers["Cookie"])
        assertEquals("encoded-code", headers["x-access-code"])
        assertEquals("app", headers["x-access-source"])
    }

    @Test
    fun `playback keeps fifty seconds forward and fifteen seconds back`() {
        val loadControl = createPlaybackLoadControl()

        assertEquals(
            FORWARD_BUFFER_DURATION_MS * 1_000L,
            loadControl.privateDurationUs("maxBufferUs"),
        )
        assertEquals(
            BACK_BUFFER_DURATION_MS * 1_000L,
            loadControl.privateDurationUs("backBufferDurationUs"),
        )
        assertFalse(loadControl.privateBoolean("retainBackBufferFromKeyframe"))
    }

    @Test
    fun `legacy media cleanup is scoped and idempotent`() {
        val cacheDirectory = temporaryFolder.newFolder("cache")
        val artwork = File(cacheDirectory, "artwork/cover").apply {
            parentFile?.mkdirs()
            writeText("artwork")
        }
        val legacySpan = File(cacheDirectory, "media/0/track.span").apply {
            parentFile?.mkdirs()
            writeText("audio")
        }

        assertTrue(legacySpan.exists())
        assertTrue(deleteLegacyAudioCache(cacheDirectory))
        assertFalse(File(cacheDirectory, "media").exists())
        assertTrue(artwork.exists())

        assertTrue(deleteLegacyAudioCache(cacheDirectory))
        assertFalse(File(cacheDirectory, "media").exists())
    }

    @Test
    fun `legacy cleanup does not follow a media symlink`() {
        val cacheDirectory = temporaryFolder.newFolder("symlink-cache")
        val targetDirectory = temporaryFolder.newFolder("audio-target")
        val targetFile = File(targetDirectory, "track.span").apply { writeText("audio") }
        val mediaLink = File(cacheDirectory, "media").toPath()
        try {
            Files.createSymbolicLink(mediaLink, targetDirectory.toPath())
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        } catch (error: SecurityException) {
            assumeNoException(error)
        }

        assertTrue(deleteLegacyAudioCache(cacheDirectory))
        assertFalse(Files.exists(mediaLink, LinkOption.NOFOLLOW_LINKS))
        assertTrue(targetFile.exists())
    }

    private fun DefaultLoadControl.privateDurationUs(name: String): Long =
        DefaultLoadControl::class.java.getDeclaredField(name).run {
            isAccessible = true
            getLong(this@privateDurationUs)
        }

    private fun DefaultLoadControl.privateBoolean(name: String): Boolean =
        DefaultLoadControl::class.java.getDeclaredField(name).run {
            isAccessible = true
            getBoolean(this@privateBoolean)
        }
}
