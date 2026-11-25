// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.storage

import com.mocharealm.bell.data.network.wireJson
import com.mocharealm.bell.data.playback.StreamingAudioCache
import com.mocharealm.bell.data.playback.atomicMediaText
import com.mocharealm.bell.data.playback.mediaHash
import com.mocharealm.bell.data.playback.mediaScope
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import com.mocharealm.bell.domain.repository.PlaybackSource
import com.mocharealm.bell.domain.repository.SavedLibrary
import com.mocharealm.bell.domain.repository.SavedPlayback
import io.ktor.client.request.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Account-scoped, durable media/metadata. Stream cache eviction never deletes explicit downloads.
 */
class FileMediaLibrary(
    private val root: File,
    val audioCache: StreamingAudioCache,
    private val legacyImport: suspend (Session, Item, File) -> Boolean = { _, _, _ -> false },
) : LocalMediaLibrary {
    @Serializable
    private data class Record(
        val item: Item,
        val updated: Long = 0,
        val audio: Boolean = false,
        val members: List<Item>? = null,
    )

    @Serializable
    private data class DownloadRequest(val item: Item, val tracks: List<Item> = emptyList())

    @Serializable private data class Index(val entries: Map<String, Record> = emptyMap())

    private class Account(val folder: File) {
        val lock = Mutex()
        @Volatile
        var requests = runCatching {
            wireJson.decodeFromString<Map<String, DownloadRequest>>(
                File(folder, "downloads.json").readText()
            )
        }
            .getOrDefault(emptyMap())
        var playbackStamp = runCatching {
            wireJson
                .decodeFromString<SavedPlayback>(File(folder, "playback.json").readText())
                .capturedAt
        }
            .getOrDefault(0)
        @Volatile
        var index = runCatching {
            wireJson.decodeFromString<Index>(File(folder, "index.json").readText())
        }
            .getOrDefault(Index())
        val art =
            ConcurrentHashMap<String, Long>().apply {
                File(folder, "art")
                    .listFiles()
                    ?.filter { it.extension == "image" && it.length() > 16 }
                    ?.forEach { put(it.name, it.lastModified()) }
            }

        fun directory(id: String) = File(folder, "items/" + mediaHash(id)).apply { mkdirs() }
    }

    private val accounts = ConcurrentHashMap<String, Account>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val artworkLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshLock = Mutex()
    private val requestLocks = ConcurrentHashMap<String, Mutex>()
    private val runningDownloads = ConcurrentHashMap<String, Job>()
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val imported = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val downloadProgress = progress.asStateFlow()
    private val changes = MutableStateFlow(0)
    override val revision = changes.asStateFlow()

    private suspend fun account(s: Session) =
        withContext(Dispatchers.IO) {
            accounts.computeIfAbsent(mediaScope(s)) { Account(File(root, it).apply { mkdirs() }) }
        }

    private fun audioSource(api: MusicRepository, s: Session, item: Item) =
        PlaybackSource(api.stream(s, item.id), mediaScope(s) + "/" + item.id)

    private suspend fun put(a: Account, record: Record) =
        a.lock.withLock {
            val current = a.index.entries[record.item.id]
            val present =
                File(a.folder, "items/" + mediaHash(record.item.id) + "/audio.media").let {
                    it.isFile && it.length() > 0
                }
            val merged =
                record.copy(
                    audio = (record.audio || current?.audio == true) && present,
                    members = record.members ?: current?.members,
                )
            a.index = Index(a.index.entries + (record.item.id to merged))
            atomicMediaText(File(a.folder, "index.json"), wireJson.encodeToString(a.index))
            changes.update { it + 1 }
        }

    override suspend fun source(
        api: MusicRepository,
        session: Session,
        item: Item,
    ): PlaybackSource =
        withContext(Dispatchers.IO) {
            val a = account(session)
            val target = File(a.directory(item.id), "audio.media")
            if (a.index.entries[item.id]?.audio == true && target.isFile && target.length() > 0)
                return@withContext PlaybackSource(
                    target.toURI().toString(),
                    mediaScope(session) + "/" + item.id,
                )
            if (
                downloadLocks[mediaScope(session) + item.id]?.isLocked != true &&
                    imported.add(mediaScope(session) + item.id) &&
                    legacyImport(session, item, target) &&
                    target.length() > 0
            ) {
                put(a, a.index.entries[item.id]?.copy(audio = true) ?: Record(item, audio = true))
                scope.launch { runCatching { metadata(api, session, item) } }
                return@withContext PlaybackSource(
                    target.toURI().toString(),
                    mediaScope(session) + "/" + item.id,
                )
            }
            val source = audioSource(api, session, item)
            audioCache.completed(source)?.let {
                return@withContext source.copy(url = it.toURI().toString())
            }
            source
        }

    override suspend fun prefetch(api: MusicRepository, session: Session, item: Item) {
        val source = source(api, session, item)
        if (!source.url.startsWith("file:")) audioCache.prefetch(source)
    }

    override fun downloaded(session: Session) =
        accounts[mediaScope(session)]
            ?.index
            ?.entries
            ?.values
            ?.filter { it.audio }
            ?.map { it.item.id }
            ?.toSet()
            .orEmpty()

    override fun downloadedCollections(session: Session): Set<String> {
        val a = accounts[mediaScope(session)] ?: return emptySet()
        val downloaded = downloaded(session)
        return a.index.entries.values
            .filter {
                it.members?.isNotEmpty() == true && it.members.all { song -> song.id in downloaded }
            }
            .map { it.item.id }
            .toSet()
    }

    override suspend fun cancelDownload(session: Session, item: Item) {
        val a = account(session)
        a.lock.withLock {
            a.requests = a.requests - item.id
            atomicMediaText(File(a.folder, "downloads.json"), wireJson.encodeToString(a.requests))
        }
        runningDownloads[mediaScope(session) + item.id]?.cancelAndJoin()
    }

    override suspend fun removeDownload(session: Session, item: Item) =
        withContext(Dispatchers.IO) {
            val a = account(session)
            requestLocks
                .computeIfAbsent(mediaScope(session) + item.id) { Mutex() }
                .withLock {
                    val ids =
                        if (item.type == "Audio") setOf(item.id)
                        else a.index.entries[item.id]?.members.orEmpty().map { it.id }.toSet()
                    for (id in ids) downloadLocks
                        .computeIfAbsent(mediaScope(session) + id) { Mutex() }
                        .withLock {
                            a.lock.withLock {
                                val audio = File(a.directory(id), "audio.media")
                                check(!audio.exists() || audio.delete()) {
                                    "Unable to remove downloaded audio"
                                }
                                File(audio.parentFile, "audio.media.new").let { pending ->
                                    check(!pending.exists() || pending.delete()) {
                                        "Unable to remove incomplete audio"
                                    }
                                }
                                a.index.entries[id]?.let { record ->
                                    a.index =
                                        Index(a.index.entries + (id to record.copy(audio = false)))
                                }
                                atomicMediaText(
                                    File(a.folder, "index.json"),
                                    wireJson.encodeToString(a.index),
                                )
                            }
                        }
                    a.lock.withLock {
                        a.requests = a.requests.filterKeys { it != item.id && it !in ids }
                        atomicMediaText(
                            File(a.folder, "downloads.json"),
                            wireJson.encodeToString(a.requests),
                        )
                    }
                    changes.update { it + 1 }
                }
        }

    override fun image(session: Session, item: Item): String? {
        val a = accounts[mediaScope(session)] ?: return null
        val detailed = a.index.entries[item.id]?.item ?: item
        val candidates =
            if (detailed.type == "Audio") {
                if (detailed.imageTags["Primary"] == null)
                    listOfNotNull(detailed.albumId, detailed.id)
                else listOfNotNull(detailed.id, detailed.albumId)
            } else listOf(detailed.id)
        for (id in candidates.distinct()) {
            val file = File(a.folder, "art/" + mediaHash(id) + ".image")
            if (file.isFile && file.length() > 16)
                return file.toURI().toString() + "#" + file.lastModified()
        }
        return null
    }

    private suspend fun cacheArtwork(
        api: MusicRepository,
        s: Session,
        a: Account,
        item: Item,
        force: Boolean = false,
    ): Boolean {
        return artworkLocks
            .computeIfAbsent(mediaScope(s) + item.id) { Mutex() }
            .withLock {
                val art = File(a.folder, "art/" + mediaHash(item.id) + ".image")
                if (!force && art.isFile && art.length() > 16) return@withLock true
                for (width in listOf(1200, 600)) {
                    try {
                        val bytes = api.artworkBytes(api.image(s, item, width))
                        check(bytes.size > 16) { "Empty artwork response" }
                        a.lock.withLock {
                            art.parentFile?.mkdirs()
                            val pending = File(art.parentFile, art.name + ".new")
                            pending.writeBytes(bytes)
                            java.nio.file.Files.move(
                                pending.toPath(),
                                art.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            )
                            a.art[art.name] = art.lastModified()
                        }
                        changes.update { it + 1 }
                        return@withLock true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
                art.isFile && art.length() > 16
            }
    }

    private suspend fun ensureArtwork(
        api: MusicRepository,
        s: Session,
        a: Account,
        item: Item,
        force: Boolean = false,
    ) {
        val album =
            item.albumId?.let { id ->
                a.index.entries[id]?.item ?: Item(id, item.album.orEmpty(), "MusicAlbum")
            }
        if (item.type == "Audio" && item.imageTags["Primary"] == null && album != null)
            cacheArtwork(api, s, a, album, force)
        else if (!cacheArtwork(api, s, a, item, force) && album != null)
            cacheArtwork(api, s, a, album)
    }

    override suspend fun offlineLibrary(session: Session): SavedLibrary =
        withContext(Dispatchers.IO) {
            val local = account(session)
            val records = local.index.entries.values.toList()
            val songs =
                records
                    .filter {
                        it.audio &&
                            it.item.type == "Audio" &&
                            File(local.folder, "items/" + mediaHash(it.item.id) + "/audio.media")
                                .let { file -> file.isFile && file.length() > 0 }
                    }
                    .map { it.item }
            val ids = songs.map { it.id }.toSet()
            val albums =
                records
                    .filter {
                        it.item.type == "MusicAlbum" &&
                            (songs.any { song -> song.albumId == it.item.id } ||
                                it.members.orEmpty().any { song -> song.id in ids })
                    }
                    .map { it.item }
            val playlists =
                records
                    .filter {
                        it.item.type == "Playlist" &&
                            it.members.orEmpty().any { song -> song.id in ids }
                    }
                    .map { it.item }
            val named =
                (songs.flatMap { it.artistItems } + albums.flatMap { it.albumArtists }).distinctBy {
                    it.id
                }
            val knownArtists =
                records.filter { it.item.type == "MusicArtist" }.associate { it.item.id to it.item }
            val artists =
                named
                    .map { knownArtists[it.id] ?: Item(it.id, it.name, "MusicArtist") }
                    .ifEmpty {
                        songs
                            .flatMap { it.artists }
                            .distinct()
                            .map { Item("offline-artist:$it", it, "MusicArtist") }
                    }
            SavedLibrary(albums, songs, playlists, artists)
        }

    override suspend fun item(session: Session, id: String) =
        account(session).index.entries[id]?.item

    override suspend fun tracks(session: Session, id: String) =
        account(session).index.entries[id]?.members

    override suspend fun lyrics(session: Session, id: String): LyricDocument? =
        withContext(Dispatchers.IO) {
            runCatching {
                wireJson.decodeFromString<LyricDocument>(
                    File(account(session).directory(id), "lyrics.json").readText()
                )
            }
                .getOrNull()
        }

    private suspend fun metadata(
        api: MusicRepository,
        s: Session,
        item: Item,
        members: List<Item>? = null,
    ): Record =
        withContext(Dispatchers.IO) {
            val a = account(s)
            locks
                .computeIfAbsent(mediaScope(s) + item.id) { Mutex() }
                .withLock {
                    val raw =
                        if (item.id.startsWith("jellify-"))
                            wireJson.encodeToJsonElement(item).jsonObject
                        else api.itemDocument(s, item.id)
                    val detailed = wireJson.decodeFromJsonElement<Item>(raw)
                    val lyric = if (item.type == "Audio") api.lyricDocument(s, item.id) else null
                    val old = a.index.entries[item.id]
                    val record =
                        Record(
                            detailed,
                            System.currentTimeMillis(),
                            old?.audio == true,
                            members ?: old?.members,
                        )
                    val folder = a.directory(item.id)
                    a.lock.withLock {
                        atomicMediaText(File(folder, "item.json"), raw.toString())
                        if (lyric != null)
                            atomicMediaText(File(folder, "lyrics.json"), lyric.toString())
                    }
                    ensureArtwork(
                        api,
                        s,
                        a,
                        detailed,
                        detailed.imageTags["Primary"] != old?.item?.imageTags?.get("Primary"),
                    )
                    put(a, record)
                    record
                }
        }

    private suspend fun collection(
        api: MusicRepository,
        s: Session,
        item: Item,
        provided: List<Item>,
    ): Record {
        val members =
            if (
                provided.isNotEmpty() &&
                    (item.id.startsWith("jellify-") ||
                        item.type !in listOf("MusicAlbum", "Playlist"))
            )
                provided
            else if (item.type == "Playlist") api.playlistTracks(s, item.id)
            else api.items(s, "Audio", parent = item.id)
        return metadata(api, s, item, members)
    }

    private suspend fun track(
        api: MusicRepository,
        s: Session,
        item: Item,
        includeAlbum: Boolean,
        onProgress: (Float) -> Unit = {},
    ) =
        downloadLocks
            .computeIfAbsent(mediaScope(s) + item.id) { Mutex() }
            .withLock {
                fun update(fraction: Float) {
                    progress.update { it + (item.id to fraction) }
                    onProgress(fraction)
                }
                update(0f)
                try {
                    val record = metadata(api, s, item)
                    val a = account(s)
                    if (includeAlbum)
                        record.item.albumId?.let { id ->
                            if (a.index.entries[id] == null)
                                collection(
                                    api,
                                    s,
                                    Item(id, record.item.album.orEmpty(), "MusicAlbum"),
                                    emptyList(),
                                )
                        }
                    val target = File(a.directory(item.id), "audio.media")
                    if (!target.isFile || target.length() == 0L) {
                        if (!legacyImport(s, record.item, target)) {
                            val entry = audioCache.entry(audioSource(api, s, record.item))
                            entry.acquire()
                            try {
                                val source = coroutineScope {
                                    val monitor = launch {
                                        while (isActive) {
                                            update(entry.downloadFraction())
                                            delay(100)
                                        }
                                    }
                                    try {
                                        entry.awaitComplete()
                                    } finally {
                                        monitor.cancel()
                                    }
                                }
                                val pending = File(target.parentFile, "audio.media.new")
                                source.inputStream().use { input ->
                                    pending.outputStream().use { input.copyTo(it) }
                                }
                                java.nio.file.Files.move(
                                    pending.toPath(),
                                    target.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                )
                            } finally {
                                entry.release()
                            }
                        }
                    }
                    check(target.length() > 0) { "下载文件为空" }
                    put(a, record.copy(audio = true))
                    update(1f)
                } finally {
                    progress.update { it - item.id }
                }
            }

    override suspend fun download(
        api: MusicRepository,
        session: Session,
        item: Item,
        tracks: List<Item>,
    ) =
        withContext(Dispatchers.IO) {
            val a = account(session)
            val request = DownloadRequest(item, tracks)
            a.lock.withLock {
                a.requests = a.requests + (item.id to request)
                atomicMediaText(
                    File(a.folder, "downloads.json"),
                    wireJson.encodeToString(a.requests),
                )
            }
            performDownload(api, session, request)
        }

    private suspend fun performDownload(
        api: MusicRepository,
        session: Session,
        request: DownloadRequest,
    ) {
        requestLocks
            .computeIfAbsent(mediaScope(session) + request.item.id) { Mutex() }
            .withLock {
                if (account(session).requests[request.item.id] != request) return@withLock
                coroutineScope {
                    val key = mediaScope(session) + request.item.id
                    val owner = currentCoroutineContext()[Job]!!
                    runningDownloads[key] = owner
                    try {
                        if (request.item.type == "Audio") track(api, session, request.item, true)
                        else {
                            progress.update { it + (request.item.id to 0f) }
                            try {
                                val record = collection(api, session, request.item, request.tracks)
                                val members = record.members.orEmpty()
                                for ((index, song) in members.withIndex()) track(
                                    api,
                                    session,
                                    song,
                                    false,
                                ) { fraction ->
                                    progress.update {
                                        it +
                                            (request.item.id to
                                                (index + fraction) / members.size.coerceAtLeast(1))
                                    }
                                }
                            } finally {
                                progress.update { it - request.item.id }
                            }
                        }
                        val a = account(session)
                        a.lock.withLock {
                            a.requests = a.requests - request.item.id
                            atomicMediaText(
                                File(a.folder, "downloads.json"),
                                wireJson.encodeToString(a.requests),
                            )
                        }
                    } finally {
                        runningDownloads.remove(key, owner)
                    }
                }
            }
    }

    override suspend fun refresh(api: MusicRepository, session: Session, force: Boolean) =
        withContext(Dispatchers.IO) {
            refreshLock.withLock {
                val a = account(session)
                val now = System.currentTimeMillis()
                val records =
                    a.index.entries.values.filter {
                        force || now - it.updated >= 6 * 60 * 60 * 1000L
                    }
                for (record in records) {
                    try {
                        if (record.members != null)
                            collection(api, session, record.item, emptyList())
                        else metadata(api, session, record.item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {} // Keep a complete previous snapshot while offline.
                }
                // Missing/empty artwork is repaired even when six-hour metadata is still fresh.
                for (record in a.index.entries.values) {
                    if (record.audio || record.members != null) {
                        val item = record.item
                        val ownId =
                            if (item.type == "Audio" && item.imageTags["Primary"] == null)
                                item.albumId ?: item.id
                            else item.id
                        val file = File(a.folder, "art/" + mediaHash(ownId) + ".image")
                        if (!file.isFile || file.length() <= 16)
                            ensureArtwork(api, session, a, item)
                    }
                }
                for (request in a.requests.values) {
                    if (requestLocks[mediaScope(session) + request.item.id]?.isLocked == true)
                        continue
                    try {
                        performDownload(api, session, request)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (a.requests.containsKey(request.item.id)) throw e
                    } catch (_: Exception) {}
                }
            }
        }

    override suspend fun loadPlayback(session: Session): SavedPlayback? =
        withContext(Dispatchers.IO) {
            runCatching {
                wireJson.decodeFromString<SavedPlayback>(
                    File(account(session).folder, "playback.json").readText()
                )
            }
                .getOrNull()
        }

    override suspend fun savePlayback(session: Session, playback: SavedPlayback) =
        withContext(Dispatchers.IO) {
            val a = account(session)
            a.lock.withLock {
                if (playback.capturedAt == 0L || playback.capturedAt >= a.playbackStamp) {
                    atomicMediaText(
                        File(a.folder, "playback.json"),
                        wireJson.encodeToString(playback),
                    )
                    a.playbackStamp = playback.capturedAt
                }
            }
        }

    override suspend fun loadLibrary(session: Session): SavedLibrary? =
        withContext(Dispatchers.IO) {
            runCatching {
                wireJson.decodeFromString<SavedLibrary>(
                    File(account(session).folder, "library.json").readText()
                )
            }
                .getOrNull()
        }

    override suspend fun saveLibrary(session: Session, library: SavedLibrary) =
        withContext(Dispatchers.IO) {
            val a = account(session)
            a.lock.withLock {
                atomicMediaText(File(a.folder, "library.json"), wireJson.encodeToString(library))
            }
        }

    override fun close() {
        scope.cancel()
        audioCache.close()
    }
}
