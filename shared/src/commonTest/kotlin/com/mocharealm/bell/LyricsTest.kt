// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.bell.domain.lyrics.display
import com.mocharealm.bell.domain.lyrics.translationTracks
import com.mocharealm.bell.domain.model.LyricArtist
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.LyricKind
import com.mocharealm.bell.domain.model.LyricLine
import com.mocharealm.bell.domain.model.LyricMetadata
import com.mocharealm.bell.domain.model.LyricSyllable
import com.mocharealm.bell.domain.model.LyricTrack
import com.mocharealm.bell.ui.utils.isMusicPhoneticCaption
import com.mocharealm.bell.ui.utils.musicPhoneticsForDisplay
import com.mocharealm.bell.ui.utils.needsMusicPhonetics
import kotlin.test.*

class LyricsTest {
    @Test
    fun japaneseOriginalAndFrenchTranslationPreserveSyllablesAndTicks() {
        val data =
            LyricDocument(
                    listOf(
                        LyricTrack(
                            "Main",
                            "ja",
                            listOf(
                                LyricLine(
                                    "夜の空",
                                    10_000_000,
                                    40_000_000,
                                    listOf(
                                        LyricSyllable("夜", 10_000_000, 20_000_000),
                                        LyricSyllable("の空", 20_000_000, 40_000_000),
                                    ),
                                )
                            ),
                        ),
                        LyricTrack(
                            "Translation",
                            "fr",
                            listOf(LyricLine("Le ciel nocturne", 10_000_000, 40_000_000)),
                        ),
                    )
                )
                .display(10_000)
        assertEquals(LyricKind.Karaoke, data.kind)
        val line = assertIs<KaraokeLine.MainKaraokeLine>(data.timed.lines.single())
        assertEquals("Le ciel nocturne", line.translation)
        assertEquals(1000, line.start)
        assertEquals(4000, line.end)
        assertEquals(listOf("夜", "の空"), line.syllables.map { it.content })
        assertEquals("ja", line.languageTag)
    }

    @Test
    fun legacySyncedLyricsInferEndsWithoutInventingWords() {
        val lyrics =
            LyricDocument(legacy = listOf(LyricLine("أهلاً", 0), LyricLine("سلام", 30_000_000)))
                .display(5000)
        assertEquals(LyricKind.Synced, lyrics.kind)
        assertEquals(3000, lyrics.timed.lines.first().end)
        assertEquals(5000, lyrics.timed.lines.last().end)
        assertIs<SyncedLine>(lyrics.timed.lines.first())
    }

    @Test
    fun unsyncedRemainsUntimedAndLanguageSelectable() {
        val doc =
            LyricDocument(
                listOf(
                    LyricTrack("Main", "ko", listOf(LyricLine("안녕"))),
                    LyricTrack("Translation", "es", listOf(LyricLine("Hola"))),
                    LyricTrack("Translation", "ar", listOf(LyricLine("مرحباً"))),
                )
            )
        val lyrics = doc.display(5000, "ar")
        assertEquals(LyricKind.Unsynced, lyrics.kind)
        assertEquals("مرحباً", lyrics.lines.single().second)
        assertTrue(lyrics.timed.lines.isEmpty())
    }

    @Test
    fun absentTranslationCannotStealAdjacentTimedLine() {
        val doc =
            LyricDocument(
                    listOf(
                        LyricTrack(
                            lines = listOf(LyricLine("one", 0), LyricLine("two", 10_000_000))
                        ),
                        LyricTrack("Translation", "de", listOf(LyricLine("zwei", 10_000_000))),
                    )
                )
                .display(5000)
        assertNull(doc.lines.first().second)
        assertEquals("zwei", doc.lines.last().second)
    }

    @Test
    fun brokenEndTimeCannotCrashRenderer() {
        val doc =
            LyricDocument(legacy = listOf(LyricLine("x", 20_000_000, 10_000_000))).display(5000)
        assertEquals(2000, doc.timed.lines.single().end)
    }

    @Test
    fun defaultTranslationDoesNotSelectAnUntaggedEmbeddedTrack() {
        val doc =
            LyricDocument(
                listOf(
                    LyricTrack(lines = listOf(LyricLine("one", 0), LyricLine("two", 10_000_000))),
                    LyricTrack("Translation", "zh-CN", listOf(LyricLine("一", 0))),
                    LyricTrack("Translation", "zh-Hans", listOf(LyricLine("二", 10_000_000))),
                    LyricTrack("Translation", null, listOf(LyricLine("embedded", 0))),
                )
            )
        assertEquals(listOf("一", "二"), doc.display(5000).lines.map { it.second })
        assertEquals("embedded", doc.display(5000, "").lines.first().second)
        assertEquals(2, doc.translationTracks().size)
    }

    @Test
    fun singerChangesAlternateAndGroupsDoNotFlipTheSoloist() {
        fun line(index: Int, artist: String) =
            LyricLine(
                "word",
                index * 10_000_000L,
                (index + 1) * 10_000_000L,
                listOf(LyricSyllable("word", index * 10_000_000L, (index + 1) * 10_000_000L)),
                listOf(artist),
            )
        val doc =
            LyricDocument(
                listOf(
                    LyricTrack(
                        lines =
                            listOf(
                                line(0, "v1"),
                                line(1, "v2"),
                                line(2, "v4"),
                                line(3, "v3"),
                                line(4, "v3"),
                            )
                    )
                ),
                metadata =
                    LyricMetadata(
                        listOf(
                            LyricArtist("v1", type = "person"),
                            LyricArtist("v2", type = "person"),
                            LyricArtist("v3", type = "person"),
                            LyricArtist("v4", type = "group"),
                        )
                    ),
            )
        assertEquals(
            listOf(false, true, false, false, false),
            doc.display(5000).timed.lines.map {
                (it as KaraokeLine).alignment ==
                    com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment.End
            },
        )
    }

    @Test
    fun backingVocalsInheritParentSideAndStripOnlyEnclosingMarkers() {
        val main =
            LyricLine(
                "solo",
                0,
                50_000_000,
                listOf(LyricSyllable("solo", 0, 50_000_000)),
                listOf("v2"),
            )
        val bg =
            LyricLine(
                "(answer)",
                10_000_000,
                20_000_000,
                listOf(
                    LyricSyllable("(an", 10_000_000, 15_000_000),
                    LyricSyllable("swer)", 15_000_000, 20_000_000),
                ),
            )
        val mapped =
            LyricDocument(
                    listOf(
                        LyricTrack(lines = listOf(main)),
                        LyricTrack("Background", lines = listOf(bg)),
                    )
                )
                .display(5000)
        val parent = mapped.timed.lines.single() as KaraokeLine.MainKaraokeLine
        val backing = parent.accompanimentLines!!.single()
        assertEquals(parent.alignment, backing.alignment)
        assertEquals("answer", backing.syllables.joinToString("") { it.content })
        assertEquals(listOf(1000, 1500), backing.syllables.map { it.start })
    }

    @Test
    fun latinAndPunctuationDoNotEnablePronunciation() {
        listOf(
                "We Don't Talk About Bruno",
                "déjà vu — corazón",
                "Łódź, İstanbul, Việt Nam",
                "’…_123",
            )
            .forEach {
                assertFalse(it.needsMusicPhonetics(), it)
            }
        assertTrue("東京 night".needsMusicPhonetics())
        assertTrue("안녕".needsMusicPhonetics())
        assertFalse(" _ ".isMusicPhoneticCaption())
        val lyrics =
            com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics(
                listOf(SyncedLine("Latin", null, 0, 1000, "Latin"))
            )
        assertNull((lyrics.musicPhoneticsForDisplay().lines.single() as SyncedLine).phonetic)
    }

    @Test
    fun keyedBackingCaptionCannotLeakIntoMainWhenClocksAreIdentical() {
        val main =
            LyricLine(
                "Lead",
                0,
                30_000_000,
                listOf(LyricSyllable("Lead", 0, 30_000_000)),
                sourceKey = "L1",
            )
        val background =
            LyricLine(
                "（Answer）",
                0,
                30_000_000,
                listOf(
                    LyricSyllable("（An", 0, 10_000_000),
                    LyricSyllable("swer）", 10_000_000, 30_000_000),
                ),
                sourceKey = "L1/bg/0",
                parentKey = "L1",
            )
        val translations =
            listOf(
                LyricLine("（Réponse）", 0, 30_000_000, sourceKey = "L1/bg/0", parentKey = "L1"),
                LyricLine("Voix principale", 0, 30_000_000, sourceKey = "L1"),
            )
        val display =
            LyricDocument(
                    listOf(
                        LyricTrack(lines = listOf(main)),
                        LyricTrack("Background", lines = listOf(background)),
                        LyricTrack("Translation", "fr", translations),
                    )
                )
                .display(4000)
        val parent = display.timed.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals("Voix principale", parent.translation)
        assertEquals("Réponse", parent.accompanimentLines!!.single().translation)
        assertEquals(
            "Answer",
            parent.accompanimentLines!!.single().syllables.joinToString("") { it.content },
        )
        assertEquals("Answer", display.lines.last().first)
        assertEquals("Réponse", display.lines.last().second)
    }
}
