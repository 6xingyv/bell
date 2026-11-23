// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.PhoneticLevel
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.utils.*
import com.mocharealm.accompanist.lyrics.phonetics.*
import com.mocharealm.accompanist.lyrics.phonetics.data.*

/** Thin core-to-phonetics adapter. Script dispatch and regional policy belong to the profiles. */
class ProfilePhoneticProvider(
    private val engine: PhoneticEngine,
    private val formatter: AsciiFormatter,
    private val readings: (String) -> List<ReadingOverride> = { emptyList() },
) : PhoneticProvider {
    override val phoneticLevel = PhoneticLevel.LINE

    override fun getPhonetic(string: String): String = getPhonetic(string, null)

    override fun getPhonetic(string: String, languageTag: String?): String {
        val hints =
            languageTag
                ?.takeIf { string.isNotEmpty() }
                ?.let {
                    listOf(PhoneticLanguageHint(PhoneticTextRange(0, string.length), it))
                }
                .orEmpty()
        return resolve(PhoneticRequest(string, hints)).phonetic.orEmpty()
    }

    override fun resolve(request: PhoneticRequest): PhoneticResponse {
        // Latin (including accented letters), digits and punctuation need no romanization.
        // Return aligned empty projections, not placeholder captions that enable the menu.
        if (!request.text.needsMusicPhonetics())
            return PhoneticResponse(null, request.projectionRanges.map { PhoneticProjection(null) })
        val parsed =
            engine.parse(
                request.text,
                ParseOptions(
                    hints =
                        request.hints.map {
                            LanguageHint(TextRange(it.range.start, it.range.end), it.languageTag)
                        },
                    overrides = readings(request.text),
                ),
            )
        val ranges = request.projectionRanges
        val completePartition =
            ranges.firstOrNull()?.start == 0 &&
                ranges.lastOrNull()?.end == request.text.length &&
                ranges.zipWithNext().all { (a, b) -> a.end == b.start }
        val projections =
            if (ranges.isEmpty()) emptyList()
            else if (!completePartition) {
                ranges.map {
                    val latin = formatter.formatRange(parsed, TextRange(it.start, it.end))
                    PhoneticProjection(latin?.ifBlank { null }, aligned = latin != null)
                }
            } else {
                formatter
                    .formatRanges(
                        parsed,
                        request.projectionRanges.map { TextRange(it.start, it.end) },
                    )
                    ?.map {
                        PhoneticProjection(
                            it.latin.ifBlank { null },
                            separatorBefore = it.separatorBefore,
                        )
                    } ?: request.projectionRanges.map { PhoneticProjection(null, aligned = false) }
            }
        return PhoneticResponse(
            formatter.format(parsed).latin.ifBlank { null },
            projections,
        )
    }

    fun enrich(lyrics: SyncedLyrics): SyncedLyrics = lyrics.withPhonetics(this)
}

fun createMusicPhonetics(
    packs: PackLoader,
    readings: (String) -> List<ReadingOverride> = { emptyList() },
    romanizeHangul: (String) -> String,
): ProfilePhoneticProvider {
    val cjk =
        CjkPhoneticProfile(
            listOf(
                MandarinData.mainland(packs),
                MandarinData.taiwan(packs),
                CantoneseData.hongKong(packs),
                JapaneseData.japanese(packs),
            ),
            defaultLocale = ReadingLocale.MANDARIN_CN,
        )
    return ProfilePhoneticProvider(
        PhoneticEngine(profiles = listOf(cjk, MusicHangulProfile)),
        AsciiFormatter(
            FormatOptions(passthrough = PassthroughStyle.OMIT),
            formatters =
                listOf(
                    object : PronunciationFormatter {
                        override val notation = "jellify-hangul"

                        override fun format(
                            pronunciation: Pronunciation,
                            options: FormatOptions,
                        ): String =
                            romanizeHangul(pronunciation.units.joinToString("") { it.value })
                    }
                ),
        ),
        readings,
    )
}

/**
 * The sample's legacy ICU Korean Romanization remains an optional extension, with no bundled pack.
 * Preserve a complete Hangul run as native notation. ICU is called only by the separate formatter.
 * It has no proven per-character alignment, so partial projections use the line caption fallback.
 */
private object MusicHangulProfile : PhoneticProfile {
    override fun matches(codePoint: Int): Boolean = isHangulCodePoint(codePoint)

    override fun resolve(
        text: String,
        range: TextRange,
        options: ParseOptions,
        workspace: ParseWorkspace,
    ): LanguageResult {
        val reading =
            ReadingCandidate(
                Pronunciation(
                    null,
                    listOf(ReadingUnit(range.textIn(text), sourceRange = range)),
                    notation = "jellify-hangul",
                    languageTag = "ko",
                ),
                sourceIds = listOf("jellify-icu"),
            )
        return LanguageResult(
            listOf(PhoneticSpan(range, null, Resolution.RESOLVED, reading, listOf(reading)))
        )
    }
}

@androidx.compose.runtime.Composable
internal expect fun rememberMusicPhoneticProvider(): PhoneticProvider

private val MusicLetters = Regex("\\p{L}")
private val MusicLatin = Regex("\\p{IsLatin}+")

internal fun String.needsMusicPhonetics(): Boolean =
    MusicLetters.findAll(this).any { !MusicLatin.matches(it.value) }

internal fun String?.isMusicPhoneticCaption(): Boolean = this?.any { it.isLetter() } == true

/** Discard source romanization on Latin-only lines too; preserve mixed-script source timing. */
internal fun SyncedLyrics.musicPhoneticsForDisplay(): SyncedLyrics {
    fun line(source: KaraokeLine): KaraokeLine {
        val original = source.syllables.joinToString("") { it.content }
        val needs = original.needsMusicPhonetics()
        val syllables =
            source.syllables.map { syllable ->
                if (!needs || !syllable.phonetic.isMusicPhoneticCaption())
                    syllable.copy(phonetic = null, phoneticSeparatorBefore = "")
                else syllable
            }
        val caption = source.phonetic?.takeIf { needs && it.isMusicPhoneticCaption() }
        return when (source) {
            is KaraokeLine.MainKaraokeLine ->
                source.copy(
                    syllables = syllables,
                    phonetic = caption,
                    accompanimentLines =
                        source.accompanimentLines?.map {
                            line(it) as KaraokeLine.AccompanimentKaraokeLine
                        },
                )
            is KaraokeLine.AccompanimentKaraokeLine ->
                source.copy(syllables = syllables, phonetic = caption)
        }
    }
    return copy(
        lines =
            lines.map { source ->
                when (source) {
                    is KaraokeLine -> line(source)
                    is SyncedLine ->
                        source.copy(
                            phonetic =
                                source.phonetic?.takeIf {
                                    source.content.needsMusicPhonetics() &&
                                        it.isMusicPhoneticCaption()
                                }
                        )
                    else -> source
                }
            }
    )
}
