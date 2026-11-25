// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.lyrics

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.*
import com.mocharealm.accompanist.lyrics.core.model.karaoke.mapper.stripEnclosingParentheses
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.LyricKind
import com.mocharealm.bell.domain.model.LyricLine
import com.mocharealm.bell.domain.model.LyricTrack

data class DisplayLyrics(
    val kind: LyricKind,
    val lines: List<Pair<String, String?>>,
    val timed: SyncedLyrics,
    val phonetics: List<String?> = emptyList(),
    val backgroundIndices: Set<Int> = emptySet(),
)

private fun Long.ms() = (this / 10_000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun LyricLine.startMs() = start?.ms() ?: syllables.firstNotNullOfOrNull { it.start?.ms() }

fun LyricDocument.display(durationMs: Long, translationLanguage: String? = null): DisplayLyrics {
    val main = tracks.firstOrNull { it.type.equals("Main", true) } ?: LyricTrack(lines = legacy)
    val backgrounds = tracks.filter { it.type.equals("Background", true) }
    val translations = translationTracks()
    val translation =
        if (translationLanguage == null) translations.firstOrNull()
        else
            translations.firstOrNull {
                (lyricLanguageKey(it.language) ?: "") == lyricLanguageKey(translationLanguage)
            } ?: translations.firstOrNull()
    val phonetics = tracks.filter { it.type.equals("Phonetic", true) }
    // Match TTMLParser in Lyrics UI's sample: switch sides on person changes;
    // group vocals stay left and do not change the current soloist.
    val agentTypes = metadata.artists.associate { it.id to it.type?.lowercase() }
    var right =
        (main.lines
            .firstOrNull()
            ?.artistIds
            ?.firstOrNull()
            ?.takeLastWhile { it.isDigit() }
            ?.toIntOrNull() ?: 1) % 2 == 0
    var currentPerson: String? = null
    val alignments = mutableMapOf<Int, KaraokeAlignment>()
    main.lines.indices
        .sortedBy { main.lines[it].startMs() ?: Int.MAX_VALUE }
        .forEach { index ->
            val id = main.lines[index].artistIds.singleOrNull()
            val group = main.lines[index].artistIds.size > 1 || agentTypes[id] == "group"
            if (!group && id != null) {
                if (currentPerson != null && currentPerson != id) right = !right
                currentPerson = id
            }
            alignments[index] =
                if (group || !right) KaraokeAlignment.Start else KaraokeAlignment.End
        }
    // Preserve server-aligned captions without inferring a language from the writing system.
    fun counterpart(
        track: LyricTrack?,
        index: Int,
        line: LyricLine,
        background: Boolean = false,
    ): String? {
        if (track == null) return null
        if (line.sourceKey != null) {
            val keyed = track.lines.filter { it.sourceKey == line.sourceKey }
            if (keyed.isNotEmpty()) return keyed.first().text
            // An explicit background target cannot fall back to a main caption at the same time.
            if (background) return null
        }
        val candidates = track.lines.filter { it.sourceKey == null && it.parentKey == null }
        val start = line.start ?: line.syllables.firstNotNullOfOrNull { it.start }
        if (start == null) return candidates.getOrNull(index)?.takeIf { it.start == null }?.text
        var matches = candidates.filter { it.start == start }
        if (line.artistIds.isNotEmpty())
            matches
                .filter { it.artistIds == line.artistIds }
                .takeIf { it.isNotEmpty() }
                ?.let { matches = it }
        if (line.end != null)
            matches.filter { it.end == line.end }.takeIf { it.isNotEmpty() }?.let { matches = it }
        return when {
            matches.size == 1 -> matches.single().text
            !background -> candidates.getOrNull(index)?.takeIf { it in matches }?.text
            else -> null // Identical flat vocal intervals have no unambiguous parent reference.
        } ?: candidates.getOrNull(index)?.takeIf { !background && it.start == null }?.text
    }
    fun phonetic(track: LyricTrack) =
        phonetics.firstOrNull { it.language == track.language } ?: phonetics.firstOrNull()
    fun end(track: LyricTrack, index: Int, start: Int): Int =
        (track.lines[index].end?.ms()
                ?: track.lines[index].syllables.lastOrNull { it.end != null }?.end?.ms()
                ?: track.lines.drop(index + 1).firstNotNullOfOrNull { it.startMs() }
                ?: durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .coerceAtLeast(start)
    fun syllables(line: LyricLine, start: Int, end: Int, language: String?): List<KaraokeSyllable> =
        if (line.syllables.isEmpty())
            listOf(KaraokeSyllable(line.text, start, end, languageTag = language))
        else
            line.syllables.mapIndexed { index, syllable ->
                val a = syllable.start?.ms() ?: start
                KaraokeSyllable(
                    syllable.text,
                    a,
                    (syllable.end?.ms() ?: line.syllables.getOrNull(index + 1)?.start?.ms() ?: end)
                        .coerceAtLeast(a),
                    syllable.phonetic,
                    language,
                )
            }
    val intervals =
        main.lines.mapIndexed { index, line ->
            line.startMs()?.let { start -> start to end(main, index, start) }
        }
    val attached = mutableMapOf<Int, MutableList<KaraokeLine.AccompanimentKaraokeLine>>()
    val standalone = mutableListOf<KaraokeLine.AccompanimentKaraokeLine>()
    backgrounds.forEach { track ->
        track.lines.forEachIndexed { index, line ->
            val start = line.startMs() ?: return@forEachIndexed
            val end = end(track, index, start)
            val owner =
                if (line.parentKey != null)
                    main.lines.indexOfFirst { it.sourceKey == line.parentKey }.takeIf { it >= 0 }
                else
                    intervals.indices
                        .filter { intervals[it] != null }
                        .maxByOrNull { candidate ->
                            val interval = intervals[candidate]!!
                            (minOf(interval.second, end) - maxOf(interval.first, start))
                                .coerceAtLeast(0)
                        }
                        ?.takeIf { candidate ->
                            val interval = intervals[candidate]!!
                            start < interval.second && end > interval.first ||
                                start == interval.first
                        }
            val accompaniment =
                KaraokeLine.AccompanimentKaraokeLine(
                    syllables(line, start, end, track.language ?: main.language)
                        .stripEnclosingParentheses(),
                    counterpart(translation, index, line, true)?.stripEnclosingParentheses(),
                    alignments[owner] ?: KaraokeAlignment.Start,
                    start,
                    end,
                    counterpart(phonetic(track), index, line, true)?.stripEnclosingParentheses(),
                    track.language ?: main.language,
                )
            if (owner == null) standalone += accompaniment
            else attached.getOrPut(owner) { mutableListOf() } += accompaniment
        }
    }
    val timed =
        main.lines
            .mapIndexedNotNull { index, line ->
                val interval = intervals[index] ?: return@mapIndexedNotNull null
                val (start, end) = interval
                val caption = counterpart(translation, index, line)
                val romanization = counterpart(phonetic(main), index, line)
                val backing = attached[index]?.sortedBy { it.start }
                if (line.syllables.any { it.start != null } || !backing.isNullOrEmpty())
                    KaraokeLine.MainKaraokeLine(
                        syllables(line, start, end, main.language),
                        caption,
                        alignments[index] ?: KaraokeAlignment.Start,
                        start,
                        end,
                        romanization,
                        backing,
                        main.language,
                    )
                else SyncedLine(line.text, caption, start, end, romanization, main.language)
            }
            .toMutableList<ISyncedLine>()
    timed += standalone
    val allLines = main.lines + backgrounds.flatMap { it.lines }
    val kind =
        when {
            allLines.any { it.syllables.any { s -> s.start != null } } -> LyricKind.Karaoke
            allLines.any { it.startMs() != null } -> LyricKind.Synced
            else -> LyricKind.Unsynced
        }
    val rows =
        main.lines.mapIndexed { index, line ->
            line.text to counterpart(translation, index, line)
        } +
            backgrounds.flatMap { track ->
                track.lines.mapIndexed { index, line ->
                    line.text.stripEnclosingParentheses() to
                        counterpart(translation, index, line, true)?.stripEnclosingParentheses()
                }
            }
    val pronunciation =
        main.lines.mapIndexed { index, line -> counterpart(phonetic(main), index, line) } +
            backgrounds.flatMap { track ->
                track.lines.mapIndexed { index, line ->
                    counterpart(phonetic(track), index, line, true)?.stripEnclosingParentheses()
                }
            }
    return DisplayLyrics(
        kind,
        rows,
        SyncedLyrics(timed.sortedBy { it.start }),
        pronunciation,
        (main.lines.size until rows.size).toSet(),
    )
}

/** BCP-47 aliases describe the same caption language, not another translation choice. */
private fun lyricLanguageKey(language: String?): String? {
    val tag = language?.replace('_', '-')?.lowercase() ?: return null
    return when (tag) {
        "zh-cn",
        "zh-sg",
        "zh-hans" -> "zh-hans"
        "zh-tw",
        "zh-hk",
        "zh-mo",
        "zh-hant" -> "zh-hant"
        else -> tag
    }
}

internal fun LyricDocument.translationTracks(): List<LyricTrack> =
    tracks
        .filter { it.type.equals("Translation", true) }
        .groupBy { lyricLanguageKey(it.language) }
        .values
        .map { group ->
            group
                .first()
                .copy(
                    lines =
                        group
                            .flatMap { it.lines }
                            .distinctBy {
                                listOf(
                                    it.start,
                                    it.end,
                                    it.artistIds,
                                    it.sourceKey,
                                    it.parentKey,
                                    it.text,
                                )
                            }
                )
        }
