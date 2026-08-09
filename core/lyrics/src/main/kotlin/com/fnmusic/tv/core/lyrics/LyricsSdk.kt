// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.EnhancedLrcParser
import com.mocharealm.accompanist.lyrics.core.parser.ILyricsParser
import com.mocharealm.accompanist.lyrics.core.parser.KugouKrcParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import kotlin.math.abs

private val parser = AutoParser(
    parsers = listOf(
        QqQrcParser,
        NeteaseYrcParser,
        KugouKrcParser,
        EnhancedLrcParser,
    ),
)

fun parseLyrics(
    original: String,
    translation: String? = null,
    phonetic: String? = null,
): SyncedLyrics {
    val parsed = parser.parse(original)
    val withTranslation = translation
        ?.takeIf(String::isNotBlank)
        ?.let(parser::parse)
        ?.takeIf(SyncedLyrics::hasUsableLines)
        ?.let(parsed::withTranslation)
        ?: parsed
    val withPhonetic = phonetic
        ?.takeIf(String::isNotBlank)
        ?.let(parser::parse)
        ?.takeIf(SyncedLyrics::hasUsableLines)
        ?.let(withTranslation::withPhonetic)
        ?: withTranslation
    return withPhonetic.normalizedForDisplay()
}

fun SyncedLyrics.hasUsableLines(): Boolean = lines.any { it.lyricText().isNotBlank() }

fun ISyncedLine.lyricText(): String = when (this) {
    is SyncedLine -> content
    is KaraokeLine -> syllables.joinToString(separator = "", transform = KaraokeSyllable::content)
    else -> ""
}

fun ISyncedLine.translationText(): String? = when (this) {
    is SyncedLine -> translation
    is KaraokeLine -> translation
    else -> null
}

fun SyncedLyrics.withoutWordTiming(): SyncedLyrics = copy(
    lines = lines.mapNotNull { line ->
        val content = line.lyricText().trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        SyncedLine(
            content = content,
            translation = line.translationText()?.trim()?.takeIf(String::isNotEmpty),
            start = line.start,
            end = line.end,
        )
    },
)

private fun SyncedLyrics.withTranslation(sidecar: SyncedLyrics): SyncedLyrics = copy(
    lines = alignSidecar(sidecar) { original, text -> original.withTranslation(text) },
)

private fun SyncedLyrics.withPhonetic(sidecar: SyncedLyrics): SyncedLyrics = copy(
    lines = alignSidecar(sidecar) { original, text ->
        when (original) {
            is KaraokeLine.MainKaraokeLine -> original.copy(phonetic = text)
            is KaraokeLine.AccompanimentKaraokeLine -> original.copy(phonetic = text)
            else -> original
        }
    },
)

private fun SyncedLyrics.alignSidecar(
    sidecar: SyncedLyrics,
    merge: (ISyncedLine, String) -> ISyncedLine,
): List<ISyncedLine> {
    val available = sidecar.lines.indices.toMutableSet()
    return lines.map { original ->
        val matchIndex = available.asSequence()
            .minWithOrNull(compareBy<Int> { abs(sidecar.lines[it].start - original.start) }.thenBy { it })
            ?.takeIf { abs(sidecar.lines[it].start - original.start) <= MAX_SIDECAR_DELTA_MS }
            ?: return@map original
        available.remove(matchIndex)
        merge(original, sidecar.lines[matchIndex].lyricText().trim())
    }
}

private fun ISyncedLine.withTranslation(value: String): ISyncedLine = when (this) {
    is SyncedLine -> copy(translation = value)
    is KaraokeLine.MainKaraokeLine -> copy(translation = value)
    is KaraokeLine.AccompanimentKaraokeLine -> copy(translation = value)
    else -> this
}

private fun SyncedLyrics.normalizedForDisplay(): SyncedLyrics = copy(
    lines = lines.mapNotNull(ISyncedLine::normalizeJapaneseAnnotations),
)

private fun ISyncedLine.normalizeJapaneseAnnotations(): ISyncedLine? = when (this) {
    is SyncedLine -> copy(
        content = stripJapaneseAnnotations(content).trim(),
        translation = translation?.let(::stripJapaneseAnnotations)?.trim()?.takeIf(String::isNotEmpty),
    ).takeIf { it.content.isNotEmpty() }

    is KaraokeLine.MainKaraokeLine -> copy(
        syllables = syllables.withoutJapaneseAnnotationSyllables(),
        translation = translation?.let(::stripJapaneseAnnotations)?.trim()?.takeIf(String::isNotEmpty),
        accompanimentLines = accompanimentLines?.mapNotNull { line ->
            line.normalizeJapaneseAnnotations() as? KaraokeLine.AccompanimentKaraokeLine
        },
    ).takeIf { it.syllables.any { syllable -> syllable.content.isNotBlank() } }

    is KaraokeLine.AccompanimentKaraokeLine -> copy(
        syllables = syllables.withoutJapaneseAnnotationSyllables(),
        translation = translation?.let(::stripJapaneseAnnotations)?.trim()?.takeIf(String::isNotEmpty),
    ).takeIf { it.syllables.any { syllable -> syllable.content.isNotBlank() } }

    else -> null
}

private fun List<KaraokeSyllable>.withoutJapaneseAnnotationSyllables(): List<KaraokeSyllable> {
    val combined = joinToString(separator = "", transform = KaraokeSyllable::content)
    val removed = JAPANESE_ANNOTATION.findAll(combined).flatMap { it.range.asSequence() }.toSet()
    if (removed.isEmpty()) return this
    var absoluteIndex = 0
    return mapNotNull { syllable ->
        val content = buildString {
            syllable.content.forEach { character ->
                if (absoluteIndex !in removed) append(character)
                absoluteIndex++
            }
        }
        syllable.copy(content = content).takeIf { content.isNotEmpty() }
    }
}

private fun stripJapaneseAnnotations(value: String): String = JAPANESE_ANNOTATION.replace(value, "")

object QqQrcParser : ILyricsParser {
    private val xmlContent = Regex(
        """<Lyric_1\b[^>]*\bLyricContent=\"(.*?)\"\s*/>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordMarker = Regex("""\((\d+),(\d+)\)""")

    override fun canParse(content: String): Boolean {
        val body = extractContent(content)
        return body.lineSequence().any { raw ->
            val match = linePattern.matchEntire(raw.trim()) ?: return@any false
            wordMarker.containsMatchIn(match.groupValues[3])
        }
    }

    override fun parse(content: String): SyncedLyrics = SyncedLyrics(
        lines = extractContent(content).lineSequence().mapNotNull(::parseLine).sortedBy(ISyncedLine::start).toList(),
    )

    private fun parseLine(raw: String): ISyncedLine? {
        val match = linePattern.matchEntire(raw.trim()) ?: return null
        val lineStart = match.groupValues[1].toIntOrNull() ?: return null
        val lineDuration = match.groupValues[2].toIntOrNull() ?: return null
        val content = match.groupValues[3]
        val syllables = buildList {
            var textStart = 0
            wordMarker.findAll(content).forEach { marker ->
                val text = content.substring(textStart, marker.range.first).removeSuffix("\r")
                val start = marker.groupValues[1].toIntOrNull()
                val duration = marker.groupValues[2].toIntOrNull()
                if (text.isNotEmpty() && start != null && duration != null) {
                    add(KaraokeSyllable(text, start, start + duration))
                }
                textStart = marker.range.last + 1
            }
        }
        return if (syllables.isNotEmpty()) {
            KaraokeLine.MainKaraokeLine(
                syllables = syllables,
                translation = null,
                alignment = KaraokeAlignment.Unspecified,
                start = lineStart,
                end = maxOf(lineStart + lineDuration, syllables.last().end),
            )
        } else {
            content.trim().takeIf(String::isNotEmpty)?.let { text ->
                SyncedLine(text, null, lineStart, lineStart + lineDuration)
            }
        }
    }

    private fun extractContent(value: String): String = xmlContent.find(value)
        ?.groupValues
        ?.get(1)
        ?.let(::decodeXml)
        ?: value
}

private fun decodeXml(value: String): String = value
    .replace("&#10;", "\n")
    .replace("&#13;", "\r")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) }.orEmpty()
    }

private val JAPANESE_ANNOTATION = Regex("""[（(][\u3040-\u30ff\u31f0-\u31ff\uff66-\uff9fー・･\s]+[）)]""")
private const val MAX_SIDECAR_DELTA_MS = 1_500
