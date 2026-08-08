package com.fnmusic.tv.core.model.lyric

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long? = null,
    val original: String,
    val translation: String? = null,
    val romanization: String? = null,
    val words: List<LyricWord> = emptyList(),
) {
    val texts: List<String>
        get() = listOfNotNull(original, translation, romanization)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}

data class LyricTimeline(val lines: List<LyricLine>) {
    fun activeIndex(positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().startMs) return -1
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lines[middle].startMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        return high
    }
}

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(content: String): LyricTimeline {
        val timed = buildList {
            content.removePrefix("\uFEFF").lineSequence().forEachIndexed { order, raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty()) return@forEachIndexed
                val text = timestamp.replace(raw, "").trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    if (seconds > 59) return@forEach
                    val fraction = match.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        3 -> fraction.toLong()
                        else -> 0
                    }
                    add(ParsedLine(minutes * 60_000 + seconds * 1_000 + fractionMs, text, order))
                }
            }
        }
        return LyricTimeline(
            timed.sortedWith(compareBy<ParsedLine> { it.startMs }.thenBy { it.order })
                .groupBy { it.startMs }
                .map { (startMs, lines) -> lyricLine(startMs, lines.map { it.text }) },
        )
    }

    private data class ParsedLine(val startMs: Long, val text: String, val order: Int)
}

object LyricParser {
    fun parse(content: String): LyricTimeline {
        val lrc = LrcParser.parse(content)
        val yrc = YrcParser.parse(content)
        return when {
            yrc.hasTimedLyrics -> yrc.timeline
            lrc.lines.isNotEmpty() -> mergeTimelines(yrc.timeline, lrc)
            else -> yrc.timeline
        }
    }

    private fun mergeTimelines(first: LyricTimeline, second: LyricTimeline): LyricTimeline =
        LyricTimeline(
            (first.lines + second.lines)
                .groupBy(LyricLine::startMs)
                .toSortedMap()
                .map { (startMs, lines) -> lyricLine(startMs, lines.flatMap(LyricLine::texts)) },
        )
}

private data class YrcParseResult(val timeline: LyricTimeline, val hasTimedLyrics: Boolean)

private object YrcParser {
    private val json = Json
    private val lineTimestamp = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordTimestamp = Regex("""\(\d+,\d+,\d+\)""")

    fun parse(content: String): YrcParseResult {
        var hasTimedLyrics = false
        val timed = buildList {
            content.removePrefix("\uFEFF").lineSequence().forEachIndexed { order, raw ->
                parseTimedLine(raw, order)?.let {
                    hasTimedLyrics = true
                    add(it)
                } ?: parseMetadataLine(raw, order)?.let(::add)
            }
        }
        return YrcParseResult(
            timeline = LyricTimeline(
                timed.sortedWith(compareBy<ParsedLine> { it.startMs }.thenBy { it.order })
                    .groupBy { it.startMs }
                    .map { (startMs, lines) ->
                        val texts = lines.map { it.text }
                        val timed = lines.firstOrNull { it.words.isNotEmpty() }
                        lyricLine(
                            startMs = startMs,
                            texts = texts,
                            endMs = timed?.endMs,
                            words = timed?.words.orEmpty(),
                        )
                    },
            ),
            hasTimedLyrics = hasTimedLyrics,
        )
    }

    private fun parseTimedLine(raw: String, order: Int): ParsedLine? {
        val match = lineTimestamp.matchEntire(raw.trim()) ?: return null
        val startMs = match.groupValues[1].toLongOrNull() ?: return null
        match.groupValues[2].toLongOrNull() ?: return null
        val durationMs = match.groupValues[2].toLongOrNull() ?: return null
        val content = match.groupValues[3]
        val words = yrcWords(content)
        val text = wordTimestamp.replace(content, "").trim()
        return text.takeIf(String::isNotEmpty)?.let {
            ParsedLine(startMs, startMs + durationMs, it, words, order)
        }
    }

    private fun parseMetadataLine(raw: String, order: Int): ParsedLine? {
        val element = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return null
        val value = runCatching { element.jsonObject }.getOrNull() ?: return null
        val startMs = runCatching { value["t"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        val chunks = runCatching { value["c"]?.jsonArray }.getOrNull() ?: return null
        val text = chunks.joinToString("") { chunk ->
            runCatching { chunk.jsonObject["tx"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
                .orEmpty()
        }.trim()
        return text.takeIf(String::isNotEmpty)?.let {
            ParsedLine(startMs.coerceAtLeast(0), null, it, emptyList(), order)
        }
    }

    private fun yrcWords(content: String): List<LyricWord> {
        val matches = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")
        return matches.findAll(content).mapNotNull { match ->
            val startMs = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val durationMs = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val text = match.groupValues[3]
            text.takeIf(String::isNotEmpty)?.let { LyricWord(startMs, startMs + durationMs, it) }
        }.toList()
    }

    private data class ParsedLine(
        val startMs: Long,
        val endMs: Long?,
        val text: String,
        val words: List<LyricWord>,
        val order: Int,
    )
}

private fun lyricLine(
    startMs: Long,
    texts: List<String>,
    endMs: Long? = null,
    words: List<LyricWord> = emptyList(),
): LyricLine {
    val content = texts.map(String::trim).filter(String::isNotEmpty).distinct()
    return LyricLine(
        startMs = startMs,
        endMs = endMs,
        original = content.firstOrNull().orEmpty(),
        translation = content.getOrNull(1),
        romanization = content.getOrNull(2),
        words = words,
    )
}
