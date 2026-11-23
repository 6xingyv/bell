// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.credits

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mocharealm.bell.domain.model.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import com.mocharealm.bell.ui.composables.*
import com.mocharealm.bell.ui.mvi.rememberScreenViewModel
import com.mocharealm.bell.ui.nav.MusicPage
import com.mocharealm.bell.ui.utils.formatBellString
import org.jetbrains.compose.resources.stringResource

@Composable
fun CreditsScreen(model: BellStore, state: BellState, onLyrics: () -> Unit) {
    val viewModel = rememberScreenViewModel(model) { CreditsViewModel(model) }
    val observed by viewModel.state.collectAsState()
    val data = if (state === model.state.value) observed else viewModel.snapshot(state)
    val item = data.item ?: return
    val performers = stringResource(Res.string.credits_performers)
    val writers = stringResource(Res.string.credits_songwriters)
    val production = stringResource(Res.string.credits_production)
    val other = stringResource(Res.string.contributor)
    val noCredits = stringResource(Res.string.credits_unavailable)
    val loading = stringResource(Res.string.loading_credits)
    val viewLyrics = stringResource(Res.string.view_lyrics)
    val play = stringResource(Res.string.play)
    val pause = stringResource(Res.string.pause)
    val dateFormat = stringResource(Res.string.release_date_format)
    val date = item.released?.take(10)?.split('-')
    val release =
        if (date?.size == 3 && date.all { it.toIntOrNull() != null })
            formatBellString(dateFormat, date[0], date[1].toInt(), date[2].toInt())
        else item.year?.toString()
    val metadata =
        listOfNotNull(
                item.artist.takeIf { it.isNotBlank() },
                item.album?.takeIf { it.isNotBlank() },
            )
            .joinToString(" · ") +
            (release?.let {
                if (item.artist.isNotBlank() || !item.album.isNullOrBlank()) " ·\n$it" else it
            } ?: "")
    val groups =
        data.document?.groups?.takeIf { it.isNotEmpty() }
            ?: item.people
                .groupBy { person ->
                    when (person.type) {
                        "Artist",
                        "AlbumArtist",
                        "GuestStar",
                        "Conductor" -> "performer"
                        "Composer",
                        "Writer",
                        "Lyricist",
                        "Arranger" -> "composer-and-lyrics"
                        "Producer",
                        "Engineer",
                        "Mixer",
                        "Remixer" -> "production-and-engineering"
                        else -> "other"
                    }
                }
                .map { (kind, people) ->
                    CreditGroup(
                        kind,
                        "",
                        people
                            .groupBy { it.name }
                            .map { (name, credits) ->
                                val person =
                                    credits.firstOrNull { it.imageTag != null } ?: credits.first()
                                CreditArtist(
                                    person.id.orEmpty(),
                                    name,
                                    credits
                                        .mapNotNull { it.role?.takeIf { it.isNotBlank() } }
                                        .distinct(),
                                    person.id
                                        ?.takeIf { person.imageTag != null }
                                        ?.let { model.artwork(Item(it, name, "Person"), 96) },
                                )
                            },
                    )
                }
    BellFormSurface {
        CompositionLocalProvider(LocalMusicGroupedPage provides true) {
            val foreground = if (LocalMusicDark.current) Color.White else Color.Black
            val secondary = foreground.copy(alpha = .45f)
            MusicPage(item.name, largeTitle = false, horizontalInset = 16.dp) {
                item(key = "song-information") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MusicArtwork(model, state, item, Modifier.size(184.dp), 7.dp)
                        Spacer(Modifier.height(18.dp))
                        MusicText(
                            item.name,
                            20,
                            foreground,
                            FontWeight.Bold,
                            lines = 3,
                            align = TextAlign.Center,
                        )
                        MusicText(
                            metadata,
                            13,
                            secondary,
                            FontWeight.Medium,
                            Modifier.fillMaxWidth().padding(top = 3.dp),
                            lines = 4,
                            align = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        val playing = data.currentId == item.id && !data.paused
                        Row(
                            Modifier.width(184.dp)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(foreground.copy(alpha = .055f))
                                .clickable { viewModel.accept(CreditsIntent.Play) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            MusicIcon(if (playing) "pause" else "play", MusicAccent, 22.dp)
                            Spacer(Modifier.width(8.dp))
                            MusicText(
                                if (playing) pause else play,
                                20,
                                MusicAccent,
                                FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    BellGroup {
                        BellRow(
                            viewLyrics,
                            onClick = {
                                viewModel.accept(CreditsIntent.Lyrics)
                                onLyrics()
                            },
                            leading = {
                                Box(
                                    Modifier.size(30.dp)
                                        .clip(musicShape(7.dp))
                                        .border(
                                            .5.dp,
                                            foreground.copy(alpha = .16f),
                                            musicShape(7.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MusicIcon("quote.bubble", MusicAccent, 20.dp)
                                }
                            },
                        )
                    }
                }
                if (groups.isEmpty())
                    item(key = "credits-status") {
                        MusicText(
                            if (data.loading) loading else noCredits,
                            14,
                            secondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 28.dp),
                            lines = 3,
                        )
                    }
                groups.forEach { group ->
                    item(key = "credits:${group.kind}") {
                        val heading =
                            group.title.ifBlank {
                                when (group.kind) {
                                    "performer" -> performers
                                    "composer-and-lyrics" -> writers
                                    "production-and-engineering" -> production
                                    else -> other
                                }
                            }
                        MusicText(
                            heading.uppercase(),
                            13,
                            secondary,
                            FontWeight.SemiBold,
                            Modifier.padding(start = 14.dp, top = 28.dp, bottom = 12.dp),
                        )
                        BellGroup {
                            group.people.forEachIndexed { index, person ->
                                BellRow(
                                    person.name,
                                    person.roles.joinToString(", ").takeIf { it.isNotBlank() },
                                    showTopSeparator = index > 0,
                                    leading = {
                                        Box(
                                            Modifier.size(28.dp)
                                                .clip(CircleShape)
                                                .background(foreground.copy(alpha = .06f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MusicIcon("music.microphone", secondary, 16.dp)
                                            AsyncImage(
                                                person.artworkUrl,
                                                person.name,
                                                Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
