// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine

internal data class LyricsContentQualityProjection(
    val quality: LyricsContentQuality,
    val wordTimedCoverage: Double,
    val translationCoverage: Double,
    val usableLineCount: Int,
)

internal object LyricsContentQualityEvaluator {
    fun evaluate(lyrics: SyncedLyrics): LyricsContentQualityProjection {
        val lines = lyrics.lines.filter { it.lyricText().isNotBlank() }
        if (lines.isEmpty()) {
            return LyricsContentQualityProjection(LyricsContentQuality.Basic, 0.0, 0.0, 0)
        }
        val wordTimedLines = lines.count { line ->
            line is KaraokeLine && line.syllables.count { it.content.isNotBlank() && it.end > it.start } >= 2
        }
        val translatedLines = lines.count { !it.translationText().isNullOrBlank() }
        val quality = when {
            wordTimedLines > 0 -> LyricsContentQuality.WordTimed
            translatedLines > 0 -> LyricsContentQuality.Translated
            else -> LyricsContentQuality.Basic
        }
        return LyricsContentQualityProjection(
            quality = quality,
            wordTimedCoverage = wordTimedLines.toDouble() / lines.size,
            translationCoverage = translatedLines.toDouble() / lines.size,
            usableLineCount = lines.size,
        )
    }
}
