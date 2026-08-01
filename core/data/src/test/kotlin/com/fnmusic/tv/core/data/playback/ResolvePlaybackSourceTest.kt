package com.fnmusic.tv.core.data.playback

import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.PlaybackSource
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvePlaybackSourceTest {
    @Test fun `cue always resolves to hls`() {
        val track = Track(TrackGuid("t"), "Cue", null, null, null, null, true)
        assertTrue(ResolvePlaybackSource()(track) is PlaybackSource.Hls)
    }

    @Test fun `ordinary track starts direct and falls back once on decoder failure`() {
        val track = Track(TrackGuid("t"), "Direct", null, null, null, null, false)
        assertTrue(ResolvePlaybackSource()(track) is PlaybackSource.Direct)
        assertTrue(ResolvePlaybackSource()(track, directDecoderFailed = true) is PlaybackSource.Hls)
    }
}
