// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.data.network.wireJson
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.playback.mediaHash
import com.mocharealm.bell.data.playback.mediaScope
import com.mocharealm.bell.data.storage.FileMediaLibrary
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.User
import com.mocharealm.bell.domain.repository.SavedPlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class OfflineLibraryTest {
    private val session =
        Session(
            "http://library.test",
            "fixture-token",
            User("fixture-user", "fixture"),
            "test-device",
        )
    private val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    private val song = Item("song", "Song", album = "Album", albumId = "album", ticks = 900_000_000)
    private val artwork =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVxkAAAAASUVORK5CYII="
            )
    private var emptySongArtwork = false
    private var version = 1
    private var online = true
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun client() =
        HttpClient(
            MockEngine { request ->
                check(online) { "Offline fixture" }
                val path = request.url.encodedPath
                requests += path
                val body =
                    when (path) {
                        "/Items/song" ->
                            """{"Id":"song","Name":"Song $version","Type":"Audio","Album":"Album","AlbumId":"album","RunTimeTicks":900000000,"ImageTags":{"Primary":"cover"},"ExtendedMetadata":{"allFields":true}}"""
                        "/Items/album" ->
                            """{"Id":"album","Name":"Album","Type":"MusicAlbum","Overview":"Album description $version","AlbumArtists":[{"Id":"artist","Name":"Album artist"}],"ExtraAlbumField":42}"""
                        "/Items" ->
                            """{"Items":[{"Id":"song","Name":"Song $version","Type":"Audio","AlbumId":"album","RunTimeTicks":900000000}],"TotalRecordCount":1}"""
                        "/Audio/song/Lyrics" ->
                            """{"Metadata":{"Artists":[{"Id":"v1","Type":"person"}]},"Tracks":[{"Type":"Main","Lines":[{"Text":"original $version","Start":0,"End":10000000,"SourceKey":"L1"}]},{"Type":"Translation","Language":"fr","Lines":[{"Text":"français","Start":0,"SourceKey":"L1"}]},{"Type":"Translation","Language":"de","Lines":[{"Text":"Deutsch","Start":0,"SourceKey":"L1"}]},{"Type":"Phonetic","Lines":[{"Text":"reading","Start":0,"SourceKey":"L1"}]},{"Type":"Background","Lines":[{"Text":"backing","Start":0,"ParentKey":"L1","SourceKey":"L1/bg/0"}]}],"ExtraLyricField":{"kept":true}}"""
                        else -> null
                    }
                if (body != null)
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else if (path.endsWith("/Images/Primary"))
                    respond(
                        if (emptySongArtwork && path == "/Items/song/Images/Primary") byteArrayOf()
                        else artwork,
                        HttpStatusCode.OK,
                    )
                else if (path == "/Audio/song/stream")
                    respond(
                        payload,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                    )
                else error("Unexpected fixture endpoint $path")
            }
        ) {
            configureClient(this)
        }

    private fun temporary() =
        Files.createTempDirectory(
                File(System.getProperty("jellify.cacheDir", "D:/BellBuildCache"))
                    .apply { mkdirs() }
                    .toPath(),
                "offline-test-",
            )
            .toFile()

    @Test
    fun aSingleDownloadRetainsFullDocumentsAndPlaysLocallyAfterRestart() = runBlocking {
        val root = temporary()
        val client = client()
        val cache = StreamingAudioCache(File(root, "cache"), client)
        val media = FileMediaLibrary(File(root, "media"), cache)
        val api = JellyfinApi(client)
        try {
            media.download(api, session, song)
            assertTrue("song" in media.downloaded(session))
            assertEquals(5, media.lyrics(session, "song")!!.tracks.size)
            assertEquals("Album artist", media.item(session, "album")!!.albumArtists.single().name)
            assertEquals("song", media.tracks(session, "album")!!.single().id)
            val folder = File(root, "media/${mediaScope(session)}/items/${mediaHash("song")}")
            assertTrue(File(folder, "item.json").readText().contains("ExtendedMetadata"))
            assertTrue(File(folder, "lyrics.json").readText().contains("ExtraLyricField"))
            online = false
            requests.clear()
            val restarted = FileMediaLibrary(File(root, "media"), cache)
            val source = restarted.source(api, session, song)
            assertTrue(source.url.startsWith("file:"))
            assertContentEquals(payload, File(java.net.URI(source.url)).readBytes())
            assertEquals(5, restarted.lyrics(session, "song")!!.tracks.size)
            assertTrue(requests.isEmpty())
        } finally {
            media.close()
            client.close()
        }
    }

    @Test
    fun fullAlbumMetadataRefreshesOnlyWhenDueOrForced() = runBlocking {
        val root = temporary()
        val client = client()
        val media =
            FileMediaLibrary(File(root, "media"), StreamingAudioCache(File(root, "cache"), client))
        val api = JellyfinApi(client)
        try {
            media.download(api, session, Item("album", "Album", "MusicAlbum"))
            requests.clear()
            version = 2
            media.refresh(api, session, false)
            assertTrue(requests.isEmpty())
            media.refresh(api, session, true)
            assertEquals("Song 2", media.item(session, "song")!!.name)
            assertEquals("Album description 2", media.item(session, "album")!!.overview)
            assertEquals(
                "original 2",
                media.lyrics(session, "song")!!.tracks.first().lines.single().text,
            )
            assertTrue("song" in media.downloaded(session))
            val old = File(root, "media/${mediaScope(session)}/index.json")
            val raw = wireJson.parseToJsonElement(old.readText()).jsonObject
            val entries =
                raw["entries"]!!.jsonObject.mapValues { (_, record) ->
                    JsonObject(record.jsonObject + ("updated" to JsonPrimitive(0)))
                }
            old.writeText(JsonObject(raw + ("entries" to JsonObject(entries))).toString())
            val restarted = FileMediaLibrary(File(root, "media"), media.audioCache)
            version = 3
            restarted.refresh(api, session, false)
            assertEquals("Song 3", restarted.item(session, "song")!!.name)
        } finally {
            media.close()
            client.close()
        }
    }

    @Test
    fun checkpointsPreserveQueueOrderProgressModesAndAccountIsolation() = runBlocking {
        val root = temporary()
        val client = client()
        val media =
            FileMediaLibrary(File(root, "media"), StreamingAudioCache(File(root, "cache"), client))
        try {
            val saved =
                SavedPlayback(listOf(song, Item("next", "Next")), 1, 12345, true, true, true)
            media.savePlayback(session, saved)
            val restarted = FileMediaLibrary(File(root, "media"), media.audioCache)
            assertEquals(saved, restarted.loadPlayback(session))
            assertNull(restarted.loadPlayback(session.copy(user = User("another"))))
            restarted.savePlayback(session, saved.copy(positionMs = 6000, capturedAt = 200))
            restarted.savePlayback(session, saved.copy(positionMs = 9000, capturedAt = 100))
            assertEquals(6000L, restarted.loadPlayback(session)!!.positionMs)
        } finally {
            media.close()
            client.close()
        }
    }

    @Test
    fun emptyDownloadedArtworkIsRepairedAndFallsBackWithoutDownloadingAudioAgain() = runBlocking {
        val root = temporary()
        val client = client()
        val media =
            FileMediaLibrary(File(root, "media"), StreamingAudioCache(File(root, "cache"), client))
        val api = JellyfinApi(client)
        try {
            media.download(api, session, song)
            val art = File(root, "media/${mediaScope(session)}/art/${mediaHash("song")}.image")
            val albumArt =
                File(root, "media/${mediaScope(session)}/art/${mediaHash("album")}.image")
            art.writeBytes(byteArrayOf())
            assertTrue(media.image(session, song)!!.contains(mediaHash("album")))
            requests.clear()
            media.refresh(api, session, false)
            assertContentEquals(artwork, art.readBytes())
            assertTrue(media.image(session, song)!!.contains(mediaHash("song")))
            assertTrue(requests.none { it.endsWith("/stream") })
            art.writeBytes(byteArrayOf())
            emptySongArtwork = true
            media.refresh(api, session, false)
            assertContentEquals(artwork, albumArt.readBytes())
            assertTrue(media.image(session, song)!!.contains(mediaHash("album")))
            assertTrue("song" in media.downloaded(session))
        } finally {
            media.close()
            client.close()
        }
    }
}
