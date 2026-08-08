// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.lyrics

internal data class LyricsContentQualityProjection(
    val quality: LyricsContentQuality,
    val wordTimedCoverage: Double,
    val translationCoverage: Double,
    val timedLineCoverage: Double,
    val usableLineCount: Int,
)

internal object LyricsContentQualityEvaluator {
    fun evaluate(lyrics: SourceLyrics): LyricsContentQualityProjection {
        val originalLines = lyrics.original.lines.filter { it.text.isNotBlank() }
        if (originalLines.isEmpty()) {
            return LyricsContentQualityProjection(
                quality = LyricsContentQuality.Basic,
                wordTimedCoverage = 0.0,
                translationCoverage = 0.0,
                timedLineCoverage = 0.0,
                usableLineCount = 0,
            )
        }

        val wordTimedLineCount = originalLines.count { line ->
            line.words.count { word ->
                word.text.isNotBlank() &&
                    word.startMs != null &&
                    word.endMs != null &&
                    word.endMs > word.startMs
            } >= MIN_TIMED_WORDS_PER_LINE
        }
        val translatedLineCount = countAlignedTranslations(originalLines, lyrics.translation)
        val quality = when {
            wordTimedLineCount > 0 -> LyricsContentQuality.WordTimed
            translatedLineCount > 0 -> LyricsContentQuality.Translated
            else -> LyricsContentQuality.Basic
        }
        return LyricsContentQualityProjection(
            quality = quality,
            wordTimedCoverage = wordTimedLineCount.toDouble() / originalLines.size,
            translationCoverage = translatedLineCount.toDouble() / originalLines.size,
            timedLineCoverage = originalLines.count { it.startMs != null }.toDouble() / originalLines.size,
            usableLineCount = originalLines.size,
        )
    }

    private fun countAlignedTranslations(
        originalLines: List<TimedLyricsLine>,
        translation: TimedLyricsTrack?,
    ): Int {
        val translationLines = translation?.lines?.filter { it.text.isNotBlank() }.orEmpty()
        if (translationLines.isEmpty()) return 0

        val available = translationLines.indices.toMutableSet()
        return originalLines.mapIndexedNotNull { originalIndex, original ->
            val matchIndex = if (original.startMs != null) {
                available.asSequence()
                    .filter { index ->
                        translationLines[index].startMs != null &&
                            normalizeLine(translationLines[index].text) != normalizeLine(original.text)
                    }
                    .minWithOrNull(
                        compareBy<Int> { index ->
                            kotlin.math.abs(translationLines[index].startMs!! - original.startMs)
                        }.thenBy { it },
                    )
                    ?.takeIf { index ->
                        kotlin.math.abs(translationLines[index].startMs!! - original.startMs) <=
                            MAX_ALIGNMENT_DELTA_MS
                    }
            } else {
                originalIndex.takeIf { index ->
                    index in available &&
                        translationLines[index].startMs == null &&
                        normalizeLine(translationLines[index].text) != normalizeLine(original.text)
                } ?: available.firstOrNull { index ->
                    translationLines[index].startMs == null &&
                        normalizeLine(translationLines[index].text) != normalizeLine(original.text)
                }
            }
            val translated = matchIndex?.let(translationLines::get) ?: return@mapIndexedNotNull null
            available.remove(matchIndex)
            translated
        }.size
    }

    private fun normalizeLine(value: String): String = LyricsCandidateScorer.normalize(value)
        .replace(" ", "")

    private const val MIN_TIMED_WORDS_PER_LINE = 2
    private const val MAX_ALIGNMENT_DELTA_MS = 1_500L
}
