// SPDX-License-Identifier: GPL-3.0-only
// Parsing behavior is derived from LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

object LyricsTextParser {
    private val lrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metadataTag = Regex("^\\[([A-Za-z]+):(.*)]$")
    private val yrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val yrcWord = Regex("(?:\\[\\d+,\\d+])?\\((\\d+),(\\d+),\\d+\\)(.*?)(?=(?:\\[\\d+,\\d+])?\\(\\d+,\\d+,\\d+\\)|$)")
    private val qrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val qrcWord = Regex("(.*?)(?:\\((\\d+),(\\d+)\\))(?=.|$)")
    private val krcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val krcWord = Regex("(?:\\[\\d+,\\d+])?<(?:(\\d+),(\\d+),\\d+)>(.*?)(?=(?:\\[\\d+,\\d+])?<\\d+,\\d+,\\d+>|$)")
    private val qrcContent = Regex("<Lyric_1\\s+LyricType=\"1\"\\s+LyricContent=\"(.*?)\"\\s*/>", RegexOption.DOT_MATCHES_ALL)

    fun parseLrc(text: String, kind: LyricsTrackKind = LyricsTrackKind.Original): TimedLyricsTrack {
        val lines = buildList {
            text.lineSequence().forEach { raw ->
                val timestamps = lrcTimestamp.findAll(raw).toList()
                if (timestamps.isEmpty()) return@forEach
                val content = raw.substring(timestamps.last().range.last + 1).trim()
                if (content.isBlank()) return@forEach
                timestamps.forEach { timestamp ->
                    add(
                        TimedLyricsLine(
                            startMs = timestampToMs(timestamp),
                            words = listOf(TimedLyricsWord(text = content)),
                        ),
                    )
                }
            }
        }.sortedBy { it.startMs }
        return TimedLyricsTrack(kind, inferLineEnds(lines))
    }

    fun parsePlain(text: String, kind: LyricsTrackKind = LyricsTrackKind.Original): TimedLyricsTrack =
        TimedLyricsTrack(
            kind,
            text.lineSequence()
                .map { it.trim().removePrefix("\uFEFF") }
                .filter(String::isNotBlank)
                .filterNot(metadataTag::matches)
                .map { TimedLyricsLine(words = listOf(TimedLyricsWord(text = it))) }
                .toList(),
        )

    fun parseYrc(text: String, kind: LyricsTrackKind = LyricsTrackKind.Original): TimedLyricsTrack =
        TimedLyricsTrack(kind, parseDurationFormat(text, yrcLine, yrcWord, wordStartsAreRelative = false))

    fun parseQrc(text: String, kind: LyricsTrackKind = LyricsTrackKind.Original): TimedLyricsTrack {
        val content = qrcContent.find(text)?.groupValues?.get(1) ?: text
        if (!content.contains(qrcLine)) return parseLrcOrPlain(content, kind)
        return TimedLyricsTrack(
            kind,
            parseDurationFormat(
                content,
                qrcLine,
                qrcWord,
                wordStartsAreRelative = false,
                wordStartGroup = 2,
                wordDurationGroup = 3,
                wordTextGroup = 1,
            ),
        )
    }

    fun parseKrc(text: String, kind: LyricsTrackKind = LyricsTrackKind.Original): TimedLyricsTrack {
        if (!text.contains(krcLine)) return parseLrcOrPlain(text, kind)
        return TimedLyricsTrack(kind, parseDurationFormat(text, krcLine, krcWord, wordStartsAreRelative = true))
    }

    fun parseLrcOrPlain(text: String, kind: LyricsTrackKind): TimedLyricsTrack {
        val lrc = parseLrc(text, kind)
        return if (lrc.isNotEmpty) lrc else parsePlain(text, kind)
    }

    fun parseMultiTrackLrc(text: String): SourceLyrics {
        val byOccurrence = mutableListOf<MutableList<TimedLyricsLine>>()
        val seenAtStart = mutableMapOf<Long, Int>()
        text.lineSequence().forEach { raw ->
            if (metadataTag.matches(raw.trim())) return@forEach
            val timestamps = lrcTimestamp.findAll(raw).toList()
            if (timestamps.isEmpty()) return@forEach
            val content = raw.substring(timestamps.last().range.last + 1).trim()
            if (content.isBlank()) return@forEach
            timestamps.forEach { timestamp ->
                val start = timestampToMs(timestamp)
                val occurrence = seenAtStart.getOrDefault(start, 0)
                seenAtStart[start] = occurrence + 1
                while (byOccurrence.size <= occurrence) byOccurrence.add(mutableListOf())
                byOccurrence[occurrence] += TimedLyricsLine(start, words = listOf(TimedLyricsWord(text = content)))
            }
        }
        val original = byOccurrence.firstOrNull()?.let(::inferLineEnds).orEmpty()
        val translation = byOccurrence.getOrNull(1)?.let(::inferLineEnds).orEmpty()
        return SourceLyrics(
            original = TimedLyricsTrack(LyricsTrackKind.Original, original),
            translation = translation.takeIf(List<TimedLyricsLine>::isNotEmpty)
                ?.let { TimedLyricsTrack(LyricsTrackKind.Translation, it) },
        )
    }

    private fun parseDurationFormat(
        text: String,
        linePattern: Regex,
        wordPattern: Regex,
        wordStartsAreRelative: Boolean,
        wordStartGroup: Int = 1,
        wordDurationGroup: Int = 2,
        wordTextGroup: Int = 3,
    ): List<TimedLyricsLine> = text.lineSequence().mapNotNull { raw ->
        val line = linePattern.find(raw.trim()) ?: return@mapNotNull null
        val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val duration = line.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val content = line.groupValues[3]
        val words = wordPattern.findAll(content).mapNotNull { match ->
            val wordStart = match.groupValues.getOrNull(wordStartGroup)?.toLongOrNull() ?: return@mapNotNull null
            val wordDuration = match.groupValues.getOrNull(wordDurationGroup)?.toLongOrNull() ?: return@mapNotNull null
            val wordText = match.groupValues.getOrNull(wordTextGroup).orEmpty()
            if (wordText.isEmpty()) return@mapNotNull null
            val absoluteStart = if (wordStartsAreRelative) start + wordStart else wordStart
            TimedLyricsWord(absoluteStart, absoluteStart + wordDuration, wordText)
        }.toList().ifEmpty {
            val cleaned = content
                .replace(Regex("(?:\\[\\d+,\\d+])?[<(]\\d+,\\d+(?:,\\d+)?[>)]"), "")
                .trim()
            listOfNotNull(cleaned.takeIf(String::isNotBlank)?.let { TimedLyricsWord(start, start + duration, it) })
        }
        TimedLyricsLine(start, start + duration, words)
    }.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.toList()

    private fun timestampToMs(match: MatchResult): Long {
        val minute = match.groupValues[1].toLong()
        val second = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return (minute * 60L + second) * 1_000L + millis
    }

    private fun inferLineEnds(lines: List<TimedLyricsLine>): List<TimedLyricsLine> = lines.mapIndexed { index, line ->
        if (line.endMs != null) line else line.copy(endMs = lines.getOrNull(index + 1)?.startMs)
    }
}

object LyricsTrackAligner {
    fun align(original: TimedLyricsTrack, translation: TimedLyricsTrack?): List<TimedLyricsLine> {
        if (translation == null || !translation.isNotEmpty) return original.lines
        val available = translation.lines.indices.toMutableSet()
        return original.lines.map { originalLine ->
            val originalStart = originalLine.startMs
            val matchIndex = when {
                originalStart == null -> null
                else -> available.minWithOrNull(
                    compareBy<Int> { index -> kotlin.math.abs((translation.lines[index].startMs ?: Long.MAX_VALUE / 2) - originalStart) }
                        .thenBy { it },
                )
            }
            val translated = matchIndex?.let(translation.lines::get)
                ?.takeIf { line -> line.startMs != null && originalStart != null && kotlin.math.abs(line.startMs - originalStart) <= MAX_ALIGNMENT_DELTA_MS }
            if (translated != null) available.remove(matchIndex)
            val texts = buildList {
                originalLine.text.takeIf(String::isNotBlank)?.let(::add)
                translated?.text
                    ?.takeIf { it.isNotBlank() && normalizeLine(it) != normalizeLine(originalLine.text) }
                    ?.let(::add)
            }
            originalLine.copy(words = texts.map { TimedLyricsWord(originalLine.startMs, originalLine.endMs, it) })
        }
    }

    private fun normalizeLine(value: String): String = value
        .replace(Regex("[（(][^）)]*[）)]|\\s+"), "")
        .trim()

    private const val MAX_ALIGNMENT_DELTA_MS = 1_500L
}
