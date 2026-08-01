package com.fnmusic.tv.core.model.lyric

data class LyricLine(val startMs: Long, val texts: List<String>)

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
                .map { (startMs, lines) -> LyricLine(startMs, lines.map { it.text }) },
        )
    }

    private data class ParsedLine(val startMs: Long, val text: String, val order: Int)
}
