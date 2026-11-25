// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import com.mocharealm.bell.domain.model.AudioPlayer
import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.MusicError
import com.mocharealm.bell.domain.model.MusicErrorKind
import com.mocharealm.bell.domain.model.MusicMix
import com.mocharealm.bell.domain.model.PlaybackHistoryEntry
import com.mocharealm.bell.domain.model.PlaylistInfo
import com.mocharealm.bell.domain.model.SearchScope
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.SessionStore
import com.mocharealm.bell.domain.model.defaultMusicError
import com.mocharealm.bell.domain.model.musicRequire
import com.mocharealm.bell.domain.playback.monotonicMillis
import com.mocharealm.bell.domain.repository.LocalMediaLibrary
import com.mocharealm.bell.domain.repository.MusicRepository
import com.mocharealm.bell.domain.repository.NoLocalMediaLibrary
import com.mocharealm.bell.domain.repository.SavedLibrary
import com.mocharealm.bell.domain.repository.SavedPlayback
import com.mocharealm.bell.domain.usecase.LoadMusicLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class BellStore(
    val repository: MusicRepository,
    private val store: SessionStore,
    val player: AudioPlayer,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val media: LocalMediaLibrary = NoLocalMediaLibrary,
    private val errorMapper: (Exception) -> MusicError = ::defaultMusicError,
) {
    private var albumRequestId = 0L

    fun accept(intent: BellIntent) {
        if (state.value.listening.active && routeListeningIntent(intent)) return
        when (intent) {
            is BellIntent.HomeVisibility -> homeVisibility(intent.visible)
            BellIntent.RefreshHome -> requestLibraryReload(false)
            BellIntent.PullToRefreshHome -> pullToRefreshHome()
            BellIntent.LoadListeningGroups -> action { listening.refreshGroups() }
            is BellIntent.CreateListeningGroup ->
                action {
                    val s = state.value
                    listeningSeed = s.queue
                    listening.create(intent.name)
                    if (s.queue.isNotEmpty())
                        listening.request(
                            "SetNewQueue",
                            buildJsonObject {
                                put("PlayingQueue", JsonArray(s.queue.map { JsonPrimitive(it.id) }))
                                put("PlayingItemPosition", s.queueIndex.coerceAtLeast(0))
                                put("StartPositionTicks", audioMutable.value.positionMs * 10_000)
                            },
                        )
                }
            is BellIntent.JoinListeningGroup -> action { listening.join(intent.id) }
            BellIntent.LeaveListeningGroup -> action { listening.leave() }
            is BellIntent.MoveQueue -> moveQueue(intent.fromKey, intent.toKey)
            is BellIntent.PlayHistory -> playHistory(intent.key)
            is BellIntent.PlayNext -> enqueueMany(listOf(intent.item), next = true)
            is BellIntent.CancelDownload -> cancelDownload(intent.item)
            is BellIntent.EnqueueMany -> enqueueMany(intent.items)
            BellIntent.ClearHistory -> {
                mutable.update { it.copy(history = emptyList()) }
                persistPlaybackNow()
            }
            BellIntent.ClearQueue -> clearQueue()
            BellIntent.AutoPlay -> {
                mutable.update { it.copy(autoPlay = !it.autoPlay) }
                extendQueue()
                persistPlaybackNow()
            }
            BellIntent.AutoMix -> {
                mutable.update { it.copy(autoMix = !it.autoMix) }
                player.setAutoMix(state.value.autoMix)
                invalidatePlan()
                persistPlaybackNow()
            }
            BellIntent.RefreshOffline -> refreshOffline()
            BellIntent.ClearError -> clearError()
            is BellIntent.Login -> login(intent.server, intent.user, intent.password)
            is BellIntent.Refresh -> refresh(intent.forceMetadata)
            is BellIntent.RemoveDownload -> removeDownload(intent.item)
            is BellIntent.Download -> download(intent.item, intent.tracks)
            BellIntent.Logout ->
                if (state.value.listening.active)
                    scope.launch {
                        optional(Unit) { listening.leave() }
                        logout()
                    }
                else logout()
            is BellIntent.OpenAlbum -> openAlbum(intent.item, intent.offlineOnly)
            BellIntent.CloseAlbum -> closeAlbum()
            is BellIntent.Search -> search(intent.query)
            is BellIntent.SearchScope -> searchScope(intent.value)
            is BellIntent.ArtistSort -> artistSort(intent.descending)
            is BellIntent.Station -> station(intent.item)
            is BellIntent.Credits -> credits(intent.item)
            BellIntent.CloseCredits -> closeCredits()
            is BellIntent.GoToArtist -> goToArtist(intent.item, intent.offlineOnly)
            is BellIntent.OpenItem ->
                openItem(intent.item, intent.rememberSearch, intent.offlineOnly)
            is BellIntent.OpenMix -> openMix(intent.mix)
            is BellIntent.Pin -> pin(intent.item)
            is BellIntent.EditPins -> editPins(intent.order, intent.removed)
            is BellIntent.SetLibrarySections -> setLibrarySections(intent.sections)
            is BellIntent.RememberSearch -> rememberSearch(intent.item)
            BellIntent.ClearSearchHistory -> clearSearchHistory()
            is BellIntent.SuggestLess -> suggestLess(intent.item)
            is BellIntent.Dialog -> dialog(intent.value)
            is BellIntent.AddToPlaylist -> addToPlaylist(intent.playlist, intent.items)
            is BellIntent.CreatePlaylist -> createPlaylist(intent.name, intent.items, intent.cover)
            is BellIntent.RenamePlaylist -> renamePlaylist(intent.item, intent.name)
            is BellIntent.Delete -> delete(intent.item)
            is BellIntent.Play -> play(intent.items, intent.index, intent.positionMs)
            is BellIntent.SetPaused -> setPaused(intent.paused)
            BellIntent.Toggle -> toggle()
            is BellIntent.Seek -> seek(intent.ms)
            is BellIntent.Next -> next(intent.automatic)
            BellIntent.Previous -> previous()
            BellIntent.Shuffle -> shuffle()
            BellIntent.Repeat -> repeat()
            is BellIntent.Enqueue -> enqueue(intent.item)
            is BellIntent.Remove -> remove(intent.index)
            is BellIntent.Favorite -> favorite(intent.item)
        }
    }

    private val loadMusicLibrary = LoadMusicLibrary(repository, media)
    private val mutable = MutableStateFlow(BellState(session = store.load()))
    val state = mutable.asStateFlow()
    private val audioMutable = MutableStateFlow(AudioSnapshot())
    val playback = audioMutable.asStateFlow()
    private val listening =
        com.mocharealm.bell.domain.playback.ListeningSession(
            repository,
            scope,
            ::applyListeningQueue,
            ::applyListeningCommand,
            { audioMutable.value },
            error = { e -> mutable.update { it.copy(error = errorMapper(e)) } },
            libraryChanged = { library ->
                if (library) {
                    albumPages.clear()
                    requestLibraryReload(true)
                } else if (homeVisible) requestLibraryReload(true)
            },
        )
    private var applyingListening = false
    private var listeningSeed: List<Item>? = null
    private val listeningKeys = mutableMapOf<String, Long>()
    private val listeningRequests = Mutex()

    private fun routeListeningIntent(intent: BellIntent): Boolean {
        if (applyingListening) return false
        val s = state.value
        val entry = s.listening.entries.getOrNull(s.queueIndex)
        fun send(operation: String, body: JsonObject = JsonObject(emptyMap())) {
            action { listeningRequests.withLock { listening.request(operation, body) } }
        }
        when (intent) {
            is BellIntent.Play -> {
                if (intent.index !in intent.items.indices) return true
                if (intent.items == s.queue)
                    send(
                        "SetPlaylistItem",
                        buildJsonObject {
                            put("PlaylistItemId", s.listening.entries[intent.index].id)
                        },
                    )
                else
                    send(
                        "SetNewQueue",
                        buildJsonObject {
                            put(
                                "PlayingQueue",
                                JsonArray(intent.items.map { JsonPrimitive(it.id) }),
                            )
                            put("PlayingItemPosition", intent.index)
                            put("StartPositionTicks", intent.positionMs * 10_000)
                        },
                    )
            }
            BellIntent.Toggle -> send(if (audioMutable.value.paused) "Unpause" else "Pause")
            is BellIntent.SetPaused -> send(if (intent.paused) "Pause" else "Unpause")
            is BellIntent.Seek ->
                send("Seek", buildJsonObject { put("PositionTicks", intent.ms * 10_000) })
            is BellIntent.Next ->
                send("NextItem", buildJsonObject { entry?.let { put("PlaylistItemId", it.id) } })
            BellIntent.Previous ->
                send(
                    "PreviousItem",
                    buildJsonObject { entry?.let { put("PlaylistItemId", it.id) } },
                )
            is BellIntent.PlayHistory ->
                s.history
                    .firstOrNull { it.key == intent.key }
                    ?.let {
                        accept(BellIntent.Play(listOf(it.item) + s.queue.drop(s.queueIndex + 1)))
                    }
            is BellIntent.PlayNext ->
                send(
                    "Queue",
                    buildJsonObject {
                        put("ItemIds", JsonArray(listOf(JsonPrimitive(intent.item.id))))
                        put("Mode", "QueueNext")
                    },
                )
            is BellIntent.Enqueue ->
                send(
                    "Queue",
                    buildJsonObject {
                        put("ItemIds", JsonArray(listOf(JsonPrimitive(intent.item.id))))
                        put("Mode", "Queue")
                    },
                )
            is BellIntent.EnqueueMany ->
                send(
                    "Queue",
                    buildJsonObject {
                        put("ItemIds", JsonArray(intent.items.map { JsonPrimitive(it.id) }))
                        put("Mode", "Queue")
                    },
                )
            is BellIntent.Remove ->
                s.listening.entries.getOrNull(intent.index)?.let {
                    send(
                        "RemoveFromPlaylist",
                        buildJsonObject {
                            put("PlaylistItemIds", JsonArray(listOf(JsonPrimitive(it.id))))
                        },
                    )
                }
            BellIntent.ClearQueue ->
                send(
                    "RemoveFromPlaylist",
                    buildJsonObject {
                        put("ClearPlaylist", true)
                        put("ClearPlayingItem", false)
                    },
                )
            is BellIntent.MoveQueue -> {
                val from = s.queueKeys.indexOf(intent.fromKey)
                val to = s.queueKeys.indexOf(intent.toKey)
                if (from < 0 || to < 0) return true
                s.listening.entries.getOrNull(from)?.let {
                    send(
                        "MovePlaylistItem",
                        buildJsonObject {
                            put("PlaylistItemId", it.id)
                            put("NewIndex", to)
                        },
                    )
                }
                moveQueue(intent.fromKey, intent.toKey)
                mutable.update {
                    it.copy(
                        listening =
                            it.listening.copy(
                                entries =
                                    s.listening.entries.toMutableList().apply {
                                        add(to, removeAt(from))
                                    }
                            )
                    )
                }
            }
            BellIntent.Shuffle ->
                send(
                    "SetShuffleMode",
                    buildJsonObject { put("Mode", if (s.shuffle) "Sorted" else "Shuffle") },
                )
            BellIntent.Repeat ->
                send(
                    "SetRepeatMode",
                    buildJsonObject {
                        put(
                            "Mode",
                            if (!s.repeat) "RepeatAll"
                            else if (!s.repeatOne) "RepeatOne" else "RepeatNone",
                        )
                    },
                )
            BellIntent.AutoPlay,
            BellIntent.AutoMix -> return true
            else -> return false
        }
        return true
    }

    private suspend fun applyListeningQueue(
        entries: List<com.mocharealm.bell.domain.model.ListeningEntry>,
        index: Int,
        position: Long,
        changed: Boolean,
    ) {
        val session = state.value.session ?: return
        // Creating a group can first broadcast an empty server session queue.
        // Keep the captured local queue until SetNewQueue's real snapshot arrives.
        if (entries.isEmpty() && !listeningSeed.isNullOrEmpty()) return
        fun id(s: String) = s.replace("-", "").lowercase()
        val s = state.value
        val known =
            (s.songs + s.albumSongs + s.queue + listeningSeed.orEmpty())
                .associateBy { id(it.id) }
                .toMutableMap()
        val missing = entries.map { it.itemId }.filter { id(it) !in known }.distinct()
        repository.byIds(session, missing).forEach { known[id(it.id)] = it }
        val items = entries.map { known[id(it.itemId)] ?: repository.item(session, it.itemId) }
        listeningSeed = null
        val keys = entries.map { listeningKeys.getOrPut(it.id) { ++nextEntryKey } }
        invalidatePlan()
        player.setAutoMix(false)
        if (index !in items.indices) {
            playJob?.cancel()
            player.stop()
            audioMutable.value = AudioSnapshot()
            mutable.update {
                it.copy(
                    queue = items,
                    queueKeys = keys,
                    queueIndex = -1,
                    audio = audioMutable.value,
                    manualQueueCount = 0,
                )
            }
            return
        }
        if (changed) {
            applyingListening = true
            try {
                play(items, index, position)
            } finally {
                applyingListening = false
            }
            // Lyrics can finish loading independently; Ready only waits for audio.
            state.first { !it.buffering }
            state.value.error?.let { throw IllegalStateException("SyncPlay audio failed to load") }
            withContext(Dispatchers.IO) { player.pause(true) }
        }
        mutable.update {
            it.copy(
                queue = items,
                queueKeys = keys,
                queueIndex = index,
                manualQueueCount = (items.size - index - 1).coerceAtLeast(0),
                autoPlay = false,
                autoMix = false,
            )
        }
        val sample = withContext(Dispatchers.IO) { player.snapshot() }.copy(revision = revision)
        audioMutable.value = sample
        mutable.update { it.copy(audio = sample, buffering = false) }
    }

    private suspend fun applyListeningCommand(command: String, position: Long?) {
        if (position != null) withContext(Dispatchers.IO) { player.seek(position.coerceAtLeast(0)) }
        when (command) {
            "Pause",
            "Stop" -> player.pause(true)
            "Unpause" -> player.pause(false)
        }
        if (command == "Stop") {
            player.stop()
            mutable.update {
                it.copy(queue = emptyList(), queueKeys = emptyList(), queueIndex = -1)
            }
        }
        val sample = withContext(Dispatchers.IO) { player.snapshot() }.copy(revision = ++revision)
        audioMutable.value = sample
        mutable.update { it.copy(audio = sample) }
    }

    val downloadProgress = media.downloadProgress
    private val nextNoticeMutable = MutableStateFlow(0)
    val nextNotice = nextNoticeMutable.asStateFlow()
    private var nextEntryKey = 0L

    private fun keys(count: Int) = List(count) { ++nextEntryKey }

    private var unshuffledKeys: List<Long> = emptyList()
    private var continuationJob: Job? = null
    private var continuationKey: Pair<Long?, Long?>? = null
    private var awaitingContinuation = false
    private var revision = 0L
    private var completedRevision = -1L
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
    private var searchJob: Job? = null
    private val actions = mutableListOf<Job>()
    private val preferencesMutex = Mutex()
    private var searchTerm = ""
    private var restoredPending = false
    private var restoringFor: Session? = state.value.session

    private data class QueuePlanKey(
        val queue: List<Item>,
        val index: Int,
        val shuffle: Boolean,
        val repeat: Boolean,
        val repeatOne: Boolean,
    )

    private fun planKey(s: BellState) =
        QueuePlanKey(s.queue, s.queueIndex, s.shuffle, s.repeat, s.repeatOne)

    private var transitionJob: Job? = null
    private var nativeTransitions = 0L
    private var plannedKey: QueuePlanKey? = null
    private var plannedNextId: String? = null

    private fun invalidatePlan() {
        plannedKey = null
        plannedNextId = null
        transitionJob?.cancel()
        player.cancelTransition()
    }

    private val downloadJobs = mutableMapOf<String, Job>()

    private fun LyricDocument.hasLyrics(): Boolean =
        legacy.isNotEmpty() || tracks.any { track -> track.lines.isNotEmpty() }

    private fun fetchLyrics(session: Session, item: Item, expectedRevision: Long) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            try {
                val cached = media.lyrics(session, item.id)
                val lyrics =
                    if (cached?.hasLyrics() == true) cached else repository.lyrics(session, item.id)
                mutable.update { current ->
                    if (
                        current.session == session &&
                            current.current?.id == item.id &&
                            revision == expectedRevision
                    )
                        current.copy(lyrics = lyrics)
                    else current
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lyrics are optional; a fetch failure must not affect audio playback.
            }
        }
    }

    fun ensureLyricsForCurrent() {
        val current = state.value
        if (current.lyrics.hasLyrics() || lyricsJob?.isActive == true) return
        val session = current.session ?: return
        val item = current.current ?: return
        fetchLyrics(session, item, revision)
    }

    private data class AlbumPage(
        val album: Item,
        val tracks: List<Item>,
        val at: Long = monotonicMillis(),
    )

    private val albumPages = mutableMapOf<Pair<Session, String>, AlbumPage>()

    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    init {
        scope.launch {
            state.map { it.session }.distinctUntilChanged().collect { listening.connect(it) }
        }
        scope.launch {
            listening.state.collect { value ->
                mutable.update {
                    it.copy(
                        listening = value,
                        shuffle = if (value.active) value.shuffle else it.shuffle,
                        repeat = if (value.active) value.repeatMode != "RepeatNone" else it.repeat,
                        repeatOne =
                            if (value.active) value.repeatMode == "RepeatOne" else it.repeatOne,
                    )
                }
            }
        }
        scope.launch {
            var buffering = false
            playback
                .map { it.buffering }
                .distinctUntilChanged()
                .collect { value ->
                    if (state.value.listening.active) {
                        try {
                            if (value) listening.buffering() else if (buffering) listening.ready()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mutable.update { it.copy(error = errorMapper(e)) }
                        }
                    }
                    buffering = value
                }
        }
        state.value.session?.let { session ->
            scope.launch {
                restoreLocal(session)
                refresh(false)
            }
        }
        scope.launch {
            media.revision.collect { version ->
                val session = state.value.session ?: return@collect
                val offline = media.offlineLibrary(session)
                val ids = media.downloaded(session)
                val current = state.value.current
                val cached = current?.let { media.item(session, it.id) }
                val lyrics = current?.let { media.lyrics(session, it.id) }
                mutable.update {
                    if (it.session == session)
                        it.copy(
                            downloaded = ids,
                            downloadedCollections = media.downloadedCollections(session),
                            offlineLibrary = offline,
                            mediaRevision = version,
                            queue =
                                if (cached != null)
                                    it.queue.map { entry ->
                                        if (entry.id == cached.id) cached else entry
                                    }
                                else it.queue,
                            lyrics = lyrics ?: it.lyrics,
                        )
                    else it
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                persistPlayback()
            }
        }
        scope.launch {
            while (isActive) {
                state.value.session?.let { session ->
                    optional(Unit) { media.refresh(repository, session) }
                }
                delay(15 * 60 * 1000L)
            }
        }
        scope.launch {
            var reports = 0
            var recoveryKey: Long? = null
            var recoveryAttempts = 0
            while (isActive) {
                delay(100)
                if (state.value.current == null || state.value.buffering || restoredPending)
                    continue
                val sampledRevision = revision
                val snapshot =
                    withContext(Dispatchers.IO) { player.snapshot() }
                        .copy(revision = sampledRevision)
                if (sampledRevision != revision) continue
                val entryKey = state.value.queueKeys.getOrNull(state.value.queueIndex)
                if (entryKey != recoveryKey) {
                    recoveryKey = entryKey
                    recoveryAttempts = 0
                }
                if (snapshot.failed && !snapshot.paused) {
                    if (recoveryAttempts < 2) {
                        recoveryAttempts++
                        val current = state.value
                        play(
                            current.queue,
                            current.queueIndex,
                            snapshot.positionMs,
                            recovering = true,
                        )
                    } else {
                        player.pause(true)
                        val stopped = snapshot.copy(paused = true, buffering = false)
                        audioMutable.value = stopped
                        mutable.update {
                            it.copy(audio = stopped, error = MusicError(MusicErrorKind.Connection))
                        }
                    }
                    continue
                }
                if (snapshot.transitionCount > nativeTransitions) {
                    nativeTransitions = snapshot.transitionCount
                    val before = state.value
                    val index = before.queueKeys.indexOf(snapshot.nativeEntryKey)
                    if (index < 0) {
                        play(before.queue, before.queueIndex, audioMutable.value.positionMs)
                        continue
                    }
                    report("/Stopped")
                    revision++
                    val sample = snapshot.copy(revision = revision)
                    val history =
                        before.current?.let {
                            (before.history + PlaybackHistoryEntry(++nextEntryKey, it)).takeLast(
                                200
                            )
                        } ?: before.history
                    mutable.update {
                        it.copy(
                            queueIndex = index,
                            history = history,
                            audio = sample,
                            lyrics = LyricDocument(),
                            manualQueueCount =
                                (it.manualQueueCount - (index - before.queueIndex).coerceAtLeast(0))
                                    .coerceAtLeast(0),
                        )
                    }
                    audioMutable.value = sample
                    plannedKey = null
                    plannedNextId = null
                    val session = before.session
                    val item = before.queue[index]
                    if (session != null) fetchLyrics(session, item, revision)
                    report("")
                    continue
                }
                audioMutable.value = snapshot
                // Position belongs to the playback clock. Do not invalidate every screen and
                // issue media-session binder transactions for each 100ms position sample.
                mutable.update { current ->
                    val old = current.audio
                    if (
                        old.paused == snapshot.paused &&
                            old.durationMs == snapshot.durationMs &&
                            old.revision == snapshot.revision &&
                            old.ended == snapshot.ended &&
                            old.buffering == snapshot.buffering &&
                            old.failed == snapshot.failed
                    )
                        current
                    else current.copy(audio = snapshot)
                }
                val current = state.value
                // Start preparing well before the end without eagerly prefetching another
                // track as soon as playback starts on short songs.
                val prefetchLeadMs =
                    minOf(
                        maxOf(90_000L, snapshot.durationMs / 2),
                        snapshot.durationMs * 4 / 5,
                    )
                if (
                    !snapshot.paused &&
                        !snapshot.buffering &&
                        snapshot.durationMs > 0 &&
                        snapshot.durationMs - snapshot.positionMs <= prefetchLeadMs &&
                        plannedKey != planKey(current)
                ) {
                    plannedKey = planKey(current)
                    val index = nextIndex(current, true, false)
                    plannedNextId = index?.let { current.queue.getOrNull(it)?.id }
                    val session = current.session
                    if (
                        !current.listening.active &&
                            session != null &&
                            index != null &&
                            index != current.queueIndex
                    ) {
                        val plan = plannedKey
                        transitionJob = scope.launch {
                            optional(Unit) {
                                media.prefetch(repository, session, current.queue[index])
                            }
                            if (current.autoMix && plannedKey == plan)
                                optional(Unit) {
                                    val source =
                                        media.source(repository, session, current.queue[index])
                                    if (plannedKey == plan)
                                        player.prepareTransition(
                                            source,
                                            current.queueKeys[index],
                                            current.queue[index].durationMs,
                                        )
                                }
                        }
                    }
                }
                extendQueue()
                if (++reports >= 100) {
                    reports = 0
                    report("/Progress")
                }
                if (snapshot.ended && completedRevision != revision) {
                    completedRevision = revision
                    if (state.value.listening.active) accept(BellIntent.Next(true))
                    else next(automatic = true)
                }
            }
        }
    }

    private fun action(block: suspend () -> Unit): Job =
        scope
            .launch {
                mutable.update { it.copy(loading = true, error = null) }
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(error = errorMapper(e)) }
                } finally {
                    mutable.update { it.copy(loading = false) }
                }
            }
            .also { job ->
                actions += job
                job.invokeOnCompletion { actions.remove(job) }
            }

    private fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    private fun login(server: String, user: String, password: String) = action {
        val session = repository.login(server, user, password, store.deviceId)
        currentCoroutineContext().ensureActive()
        store.save(session)
        mutable.update { it.copy(session = session) }
        restoreLocal(session)
        loadLibrary(session)
    }

    private val libraryLoadMutex = Mutex()
    private var lastLibraryLoad = 0L
    private var libraryReloadJob: Job? = null
    private var libraryDirty = false
    private var homeVisible = false

    private fun homeVisibility(visible: Boolean) {
        if (homeVisible == visible) return
        homeVisible = visible
        if (visible) requestLibraryReload(false)
    }

    private fun pullToRefreshHome() {
        val session = state.value.session ?: return
        if (state.value.refreshingHome) return
        mutable.update { it.copy(refreshingHome = true, error = null) }
        scope.launch {
            try {
                loadLibrary(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    if (it.session == session) it.copy(error = errorMapper(e)) else it
                }
            } finally {
                mutable.update {
                    if (it.session == session) it.copy(refreshingHome = false) else it
                }
            }
        }
    }

    private fun requestLibraryReload(force: Boolean) {
        if (
            state.value.session == null ||
                (!force && lastLibraryLoad > 0 && monotonicMillis() - lastLibraryLoad < 15_000)
        )
            return
        libraryDirty = true
        if (libraryReloadJob?.isActive == true) return
        libraryReloadJob = scope.launch {
            while (libraryDirty) {
                libraryDirty = false
                val wait =
                    if (lastLibraryLoad == 0L) 750L
                    else (lastLibraryLoad + 15_000 - monotonicMillis()).coerceAtLeast(750)
                delay(wait)
                val session = state.value.session ?: break
                optional(Unit) { loadLibrary(session) }
            }
        }
    }

    private suspend fun loadLibrary(session: Session) = libraryLoadMutex.withLock {
        val library = loadMusicLibrary.load(session)
        currentCoroutineContext().ensureActive()
        mutable.update {
            if (it.session == session)
                it.copy(
                    albums = library.albums,
                    songs = library.songs,
                    playlists = library.playlists,
                    latest = library.latest,
                    recentlyPlayed = library.recentlyPlayed,
                    recommendations = library.recommendations,
                    genres = library.genres,
                    pins = library.pins,
                    librarySections = library.librarySections ?: it.librarySections,
                    searchHistory = library.searchHistory,
                    policy = library.profile.policy,
                    userImageTag = library.profile.imageTag,
                    artists = library.artists,
                )
            else it
        }
        lastLibraryLoad = monotonicMillis()
        val mixes = loadMusicLibrary.mixes(session, library.mixSeeds)
        mutable.update { if (it.session == session) it.copy(mixes = mixes) else it }
    }

    private fun refresh(forceMetadata: Boolean = true) = action {
        state.value.session?.let { session ->
            if (forceMetadata) albumPages.clear()
            try {
                loadLibrary(session)
            } finally {
                scope.launch {
                    optional(Unit) { media.refresh(repository, session, forceMetadata) }
                }
            }
        }
    }

    private suspend fun restoreLocal(session: Session) {
        restoringFor = session
        try {
            val library = media.loadLibrary(session)
            val offline = media.offlineLibrary(session)
            val saved = media.loadPlayback(session)
            val lyrics =
                saved?.queue?.getOrNull(saved.index)?.let { media.lyrics(session, it.id) }
                    ?: LyricDocument()
            if (state.value.session != session) return
            mutable.update {
                it.copy(
                    downloaded = media.downloaded(session),
                    downloadedCollections = media.downloadedCollections(session),
                    offlineLibrary = offline,
                )
            }
            if (library != null)
                mutable.update {
                    it.copy(
                        albums = library.albums,
                        songs = library.songs,
                        playlists = library.playlists,
                        artists = library.artists,
                        pins = library.pins,
                        latest = library.latest,
                        offlineLibrary = offline,
                        downloaded = media.downloaded(session),
                        downloadedCollections = media.downloadedCollections(session),
                    )
                }
            if (
                saved != null && saved.index in saved.queue.indices && state.value.current == null
            ) {
                restoredPending = true
                val item = saved.queue[saved.index]
                val sample =
                    AudioSnapshot(
                        if (item.durationMs > 0) saved.positionMs.coerceIn(0, item.durationMs)
                        else saved.positionMs.coerceAtLeast(0),
                        item.durationMs,
                        true,
                        revision = revision,
                    )
                audioMutable.value = sample
                val restoredKeys =
                    saved.queueKeys.takeIf { it.size == saved.queue.size } ?: keys(saved.queue.size)
                val history =
                    saved.history
                        ?: saved.queue.take(saved.index).map {
                            PlaybackHistoryEntry(++nextEntryKey, it)
                        }
                nextEntryKey =
                    maxOf(
                        nextEntryKey,
                        restoredKeys.maxOrNull() ?: 0,
                        history.maxOfOrNull { it.key } ?: 0,
                    )
                unshuffledKeys = saved.unshuffledKeys
                player.setAutoMix(saved.autoMix)
                mutable.update {
                    it.copy(
                        queue = saved.queue,
                        queueKeys = restoredKeys,
                        history = history,
                        manualQueueCount =
                            saved.manualQueueCount.coerceIn(0, saved.queue.size - saved.index - 1),
                        queueContext = saved.context,
                        autoPlay = saved.autoPlay,
                        autoMix = saved.autoMix,
                        queueIndex = saved.index,
                        audio = sample,
                        shuffle = saved.shuffle,
                        repeat = saved.repeat,
                        repeatOne = saved.repeatOne,
                        lyrics = lyrics,
                    )
                }
                if (!lyrics.hasLyrics()) fetchLyrics(session, item, revision)
            }
        } finally {
            if (restoringFor == session) restoringFor = null
        }
    }

    private fun checkpoint(): Pair<Session, SavedPlayback>? {
        val s = state.value
        val session = s.session ?: return null
        if (restoringFor == session) return null
        val now = kotlin.time.Clock.System.now()
        return session to
            SavedPlayback(
                s.queue,
                s.queueIndex,
                audioMutable.value.positionMs,
                s.shuffle,
                s.repeat,
                s.repeatOne,
                now.epochSeconds * 1_000_000_000L + now.nanosecondsOfSecond,
                s.queueKeys,
                s.history,
                s.manualQueueCount,
                s.queueContext,
                s.autoPlay,
                s.autoMix,
                unshuffledKeys,
            )
    }

    suspend fun persistPlayback() {
        checkpoint()?.let { (session, saved) -> media.savePlayback(session, saved) }
    }

    fun persistPlaybackNow(): Job {
        val saved = checkpoint()
        return scope.launch(Dispatchers.IO) {
            saved?.let { media.savePlayback(it.first, it.second) }
        }
    }

    fun artwork(item: Item, width: Int = 500, offlineOnly: Boolean = false): String? =
        state.value.session?.let { artwork(it, item, width, offlineOnly) }

    internal fun artwork(session: Session, item: Item, width: Int, offlineOnly: Boolean): String? =
        media.image(session, item)
            ?: if (offlineOnly) null else repository.image(session, item, width)

    private fun download(item: Item, tracks: List<Item> = emptyList()) {
        if (downloadJobs[item.id]?.isActive == true) return
        val session = state.value.session ?: return
        val ids = (tracks.map { it.id } + item.id).toSet()
        mutable.update { it.copy(downloading = it.downloading + ids) }
        downloadJobs[item.id] = scope.launch {
            try {
                media.download(repository, session, item, tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = errorMapper(e)) }
            } finally {
                mutable.update {
                    it.copy(
                        downloading = it.downloading - ids,
                        downloaded = media.downloaded(session),
                        downloadedCollections = media.downloadedCollections(session),
                    )
                }
            }
        }
    }

    private fun cancelDownload(item: Item) = scope.launch {
        downloadJobs.remove(item.id)?.cancelAndJoin()
        state.value.session?.let { media.cancelDownload(it, item) }
    }

    private fun removeDownload(item: Item) = scope.launch {
        val session = state.value.session ?: return@launch
        try {
            media.removeDownload(session, item)
            refreshOffline().join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { if (it.session == session) it.copy(error = errorMapper(e)) else it }
        }
    }

    private fun logout() {
        albumPages.clear()
        continuationJob?.cancel()
        continuationKey = null
        awaitingContinuation = false
        unshuffledKeys = emptyList()
        report("/Stopped")
        actions.toList().forEach { it.cancel() }
        actions.clear()
        persistPlaybackNow()
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        restoredPending = false
        playJob?.cancel()
        searchJob?.cancel()
        player.stop()
        store.save(null)
        mutable.value = BellState()
        revision++
        audioMutable.value = AudioSnapshot(revision = revision)
    }

    private fun refreshOffline() = scope.launch {
        val session = state.value.session ?: return@launch
        val catalog = media.offlineLibrary(session)
        mutable.update {
            if (it.session == session)
                it.copy(
                    offlineLibrary = catalog,
                    downloaded = media.downloaded(session),
                    downloadedCollections = media.downloadedCollections(session),
                )
            else it
        }
    }

    private fun openAlbum(item: Item, offlineOnly: Boolean = false): Job {
        val requestId = ++albumRequestId
        return action {
            closeCredits()
            val cachedSession = requireNotNull(state.value.session)
            if (offlineOnly) {
                val catalog = media.offlineLibrary(cachedSession) ?: SavedLibrary()
                val album = media.item(cachedSession, item.id) ?: item
                val ids = catalog.songs.map { it.id }.toSet()
                val songs =
                    if (item.type == "MusicArtist")
                        catalog.songs.filter { song ->
                            song.artistItems.any { it.id == item.id } ||
                                item.name in song.artists ||
                                song.albumArtist == item.name
                        }
                    else
                        (media.tracks(cachedSession, item.id)
                                ?: catalog.songs.filter { it.albumId == item.id })
                            .filter { it.id in ids }
                val albums =
                    if (item.type == "MusicArtist")
                        catalog.albums.filter { a ->
                            a.albumArtists.any { it.id == item.id } ||
                                a.albumArtist == item.name ||
                                songs.any { it.albumId == a.id }
                        }
                    else emptyList()
                if (requestId != albumRequestId) return@action
                mutable.update {
                    it.copy(
                        album = album,
                        albumSongs = songs,
                        artistAlbums = albums,
                        playlistInfo = null,
                        albumOffline = true,
                        offlineLibrary = catalog,
                    )
                }
                return@action
            }
            val remembered =
                albumPages[cachedSession to item.id]?.takeIf { monotonicMillis() - it.at < 300_000 }
            val knownTracks =
                remembered?.tracks
                    ?: if (item.type == "MusicAlbum")
                        state.value.songs.filter { it.albumId == item.id }
                    else emptyList()
            if (requestId != albumRequestId) return@action
            mutable.update {
                it.copy(
                    albumOffline = false,
                    album = remembered?.album ?: item,
                    albumSongs = knownTracks,
                    artistAlbums = emptyList(),
                    playlistInfo = null,
                )
            }
            if (remembered != null && item.type == "MusicAlbum") return@action
            val cachedTracks = media.tracks(cachedSession, item.id)
            if (cachedTracks != null && item.type in listOf("MusicAlbum", "Playlist")) {
                if (requestId != albumRequestId) return@action
                val cachedAlbum = media.item(cachedSession, item.id) ?: item
                if (requestId != albumRequestId) return@action
                albumPages[cachedSession to item.id] = AlbumPage(cachedAlbum, cachedTracks)
                mutable.update {
                    if (requestId == albumRequestId && it.album?.id == item.id)
                        it.copy(album = cachedAlbum, albumSongs = cachedTracks)
                    else it
                }
                return@action
            }
            val songs =
                try {
                    when {
                        item.id.startsWith("jellify-mix:") ->
                            state.value.mixes
                                .firstOrNull { it.seed.id == item.id.removePrefix("jellify-mix:") }
                                ?.tracks
                                .orEmpty()
                        item.id.startsWith("jellify-album:") ->
                            state.value.songs.filter { it.album == item.name }
                        item.type == "Playlist" ->
                            repository.playlistTracks(requireNotNull(state.value.session), item.id)
                        item.type == "MusicGenre" ->
                            repository.query(
                                requireNotNull(state.value.session),
                                "Audio",
                                limit = 500,
                                extra = mapOf("GenreIds" to item.id),
                            )
                        item.type == "MusicArtist" ->
                            repository.query(
                                requireNotNull(state.value.session),
                                "Audio",
                                "PlayCount",
                                true,
                                500,
                                extra = mapOf("ArtistIds" to item.id),
                            )
                        else ->
                            repository.items(
                                requireNotNull(state.value.session),
                                "Audio",
                                parent = item.id,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    media.tracks(cachedSession, item.id) ?: throw e
                }
            val info =
                if (item.type == "Playlist")
                    optional<PlaylistInfo?>(null) {
                        repository.playlistInfo(requireNotNull(state.value.session), item.id)
                    }
                else null
            val artistAlbums =
                if (item.type == "MusicArtist")
                    repository.query(
                        requireNotNull(state.value.session),
                        "MusicAlbum",
                        "PremiereDate",
                        true,
                        500,
                        mapOf("ArtistIds" to item.id),
                    )
                else emptyList()
            val detailed =
                if (item.type == "MusicAlbum" && !item.id.startsWith("jellify-"))
                    optional(media.item(cachedSession, item.id) ?: item) {
                        repository.item(cachedSession, item.id)
                    }
                else item
            if (requestId == albumRequestId && state.value.session == cachedSession) {
                if (albumPages.size >= 24) albumPages.remove(albumPages.keys.first())
                albumPages[cachedSession to item.id] = AlbumPage(detailed, songs)
            }
            mutable.update {
                if (requestId == albumRequestId && it.album?.id == item.id)
                    it.copy(
                        album = detailed,
                        albumSongs = songs,
                        playlistInfo = info,
                        artistAlbums = artistAlbums,
                    )
                else it
            }
        }
    }

    private fun closeAlbum() {
        albumRequestId++
        mutable.update { it.copy(album = null, albumOffline = false, albumSongs = emptyList()) }
    }

    private fun search(query: String) {
        searchTerm = query
        searchJob?.cancel()
        mutable.update { it.copy(results = emptyList()) }
        searchJob = scope.launch {
            delay(300)
            val session = state.value.session ?: return@launch
            try {
                val results =
                    if (query.isBlank()) emptyList()
                    else
                        repository.search(
                            session,
                            query,
                            state.value.searchScope == SearchScope.Favorites,
                        )
                mutable.update { it.copy(results = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = errorMapper(e)) }
            }
        }
    }

    private fun searchScope(value: SearchScope) {
        mutable.update { it.copy(searchScope = value) }
        search(searchTerm)
    }

    private fun artistSort(descending: Boolean) {
        mutable.update { it.copy(artistDescending = descending) }
    }

    private fun station(item: Item) = action {
        val tracks = repository.mix(requireNotNull(state.value.session), item.id)
        musicRequire(tracks.isNotEmpty(), MusicErrorKind.EmptyStation)
        play(tracks)
    }

    private var creditsJob: Job? = null

    private fun closeCredits() {
        creditsJob?.cancel()
        mutable.update { it.copy(creditsItem = null, credits = null, creditsLoading = false) }
    }

    private fun credits(item: Item) {
        creditsJob?.cancel()
        val session = state.value.session ?: return
        mutable.update { it.copy(creditsItem = item, credits = null, creditsLoading = true) }
        creditsJob = action {
            val details =
                try {
                    repository.item(session, item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    media.item(session, item.id) ?: item
                }
            val document =
                try {
                    repository.credits(session, item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            mutable.update {
                if (it.session == session && it.creditsItem?.id == item.id)
                    it.copy(creditsItem = details, credits = document, creditsLoading = false)
                else it
            }
        }
    }

    private fun goToArtist(item: Item, offlineOnly: Boolean = false) = action {
        val artists =
            if (offlineOnly) state.value.offlineLibrary?.artists.orEmpty() else state.value.artists
        val artist =
            item.artistItems.firstOrNull()?.let { named ->
                artists.firstOrNull { it.id == named.id }
                    ?: Item(named.id, named.name, "MusicArtist")
            }
                ?: artists.firstOrNull { it.name in item.artists }
                ?: if (offlineOnly) null
                else
                    repository
                        .search(
                            requireNotNull(state.value.session),
                            item.artists.firstOrNull() ?: item.artist,
                            false,
                        )
                        .firstOrNull { it.type == "MusicArtist" }
        musicRequire(artist != null, MusicErrorKind.MissingArtist)
        openAlbum(requireNotNull(artist), offlineOnly)
    }

    private fun openItem(
        item: Item,
        rememberSearch: Boolean = false,
        offlineOnly: Boolean = false,
    ) {
        if (rememberSearch) rememberSearch(item)
        if (item.type == "Audio") play(listOf(item)) else openAlbum(item, offlineOnly)
    }

    private fun openMix(mix: MusicMix) {
        mutable.update {
            it.copy(
                album =
                    mix.seed.copy(
                        id = "jellify-mix:${mix.seed.id}",
                        name = mix.seed.name,
                        type = "Mix",
                    ),
                albumSongs = mix.tracks,
                playlistInfo = null,
            )
        }
    }

    private suspend fun saveItems(session: Session, key: String, items: List<Item>) =
        savePreferenceList(session, key, items.map { it.id })

    private suspend fun savePreferenceList(session: Session, key: String, values: List<String>) {
        repository.savePreferences(
            session,
            mapOf(key to JsonArray(values.map { JsonPrimitive(it) }).toString()),
        )
    }

    private fun setLibrarySections(sections: List<String>) = action {
        preferencesMutex.withLock {
            val before = state.value
            val session = before.session ?: return@withLock
            if (sections == before.librarySections) return@withLock
            mutable.update {
                if (it.session == session) it.copy(librarySections = sections) else it
            }
            try {
                savePreferenceList(session, "librarySections", sections)
            } catch (e: Exception) {
                mutable.update {
                    if (it.session == session && it.librarySections == sections)
                        it.copy(librarySections = before.librarySections)
                    else it
                }
                throw e
            }
        }
    }

    private fun editPins(order: List<String>, removed: Set<String>) = action {
        preferencesMutex.withLock {
            val before = state.value
            val session = before.session ?: return@withLock
            val byId = before.pins.filterNot { it.id in removed }.associateBy { it.id }
            val pins = (order + byId.keys).distinct().mapNotNull(byId::get)
            if (pins == before.pins) return@withLock
            mutable.update { if (it.session == session) it.copy(pins = pins) else it }
            try {
                saveItems(session, "pins", pins)
            } catch (e: Exception) {
                mutable.update {
                    if (it.session == session && it.pins == pins) it.copy(pins = before.pins)
                    else it
                }
                throw e
            }
        }
    }

    private fun pin(item: Item) = action {
        preferencesMutex.withLock {
            val session = requireNotNull(state.value.session)
            val pins =
                state.value.pins.let { list ->
                    if (list.any { it.id == item.id }) list.filterNot { it.id == item.id }
                    else list + item
                }
            saveItems(session, "pins", pins)
            mutable.update { if (it.session == session) it.copy(pins = pins) else it }
        }
    }

    private fun rememberSearch(item: Item) = action {
        preferencesMutex.withLock {
            val session = requireNotNull(state.value.session)
            val history =
                (listOf(item) + state.value.searchHistory.filterNot { it.id == item.id }).take(20)
            saveItems(session, "searchHistory", history)
            mutable.update { if (it.session == session) it.copy(searchHistory = history) else it }
        }
    }

    private fun clearSearchHistory() = action {
        preferencesMutex.withLock {
            val session = requireNotNull(state.value.session)
            saveItems(session, "searchHistory", emptyList())
            mutable.update { it.copy(searchHistory = emptyList()) }
        }
    }

    private fun suggestLess(item: Item) = action {
        repository.suggestLess(requireNotNull(state.value.session), item.id)
        mutable.update {
            it.copy(recommendations = it.recommendations.filterNot { a -> a.id == item.id })
        }
    }

    private fun dialog(value: LibraryDialog?) {
        mutable.update { it.copy(dialog = value) }
    }

    private fun addToPlaylist(playlist: Item, items: List<Item>) = action {
        repository.addToPlaylist(
            requireNotNull(state.value.session),
            playlist.id,
            items.map { it.id },
        )
        dialog(null)
        if (state.value.album?.id == playlist.id) openAlbum(playlist)
    }

    private fun createPlaylist(name: String, items: List<Item>, cover: ByteArray?) = action {
        musicRequire(name.isNotBlank(), MusicErrorKind.PlaylistNameRequired)
        val session = requireNotNull(state.value.session)
        val id = repository.createPlaylist(session, name.trim(), items.map { it.id })
        dialog(null)
        try {
            if (cover != null) repository.setPlaylistCover(session, id, cover)
        } finally {
            if (state.value.session == session) loadLibrary(session)
        }
    }

    private fun renamePlaylist(item: Item, name: String) = action {
        musicRequire(name.isNotBlank(), MusicErrorKind.PlaylistNameRequired)
        val session = requireNotNull(state.value.session)
        repository.renamePlaylist(session, item, name.trim())
        dialog(null)
        loadLibrary(session)
        if (state.value.album?.id == item.id)
            mutable.update { it.copy(album = item.copy(name = name.trim())) }
    }

    fun canDelete(item: Item): Boolean = item.canDelete ?: state.value.policy.delete

    private fun delete(item: Item) = action {
        musicRequire(canDelete(item), MusicErrorKind.DeleteForbidden)
        val session = requireNotNull(state.value.session)
        repository.deleteItem(session, item.id)
        dialog(null)
        closeAlbum()
        preferencesMutex.withLock {
            val pins = state.value.pins.filterNot { it.id == item.id }
            saveItems(session, "pins", pins)
            mutable.update { it.copy(pins = pins) }
        }
        loadLibrary(session)
    }

    private fun play(
        items: List<Item>,
        index: Int = 0,
        positionMs: Long = 0,
        recovering: Boolean = false,
    ) {
        if (index !in items.indices) return
        val previous = state.value
        val sameQueue =
            items.size == previous.queue.size &&
                items.indices.all { items[it].id == previous.queue[it].id }
        val queueKeys =
            if (sameQueue && previous.queueKeys.size == items.size) previous.queueKeys
            else keys(items.size)
        val history =
            if (previous.current != null && (!sameQueue || index != previous.queueIndex))
                (previous.history + PlaybackHistoryEntry(++nextEntryKey, previous.current!!))
                    .takeLast(200)
            else previous.history
        val manualCount =
            if (sameQueue)
                (previous.manualQueueCount - (index - previous.queueIndex).coerceAtLeast(0))
                    .coerceAtLeast(0)
            else 0
        val context =
            if (sameQueue) previous.queueContext
            else if (items == previous.albumSongs) previous.album?.name else items[index].album
        if (!sameQueue) unshuffledKeys = emptyList()
        continuationJob?.cancel()
        continuationKey = null
        awaitingContinuation = false
        if (!restoredPending) report("/Stopped")
        restoredPending = false
        invalidatePlan()
        nativeTransitions = 0
        playJob?.cancel()
        revision++
        val waiting =
            if (recovering)
                audioMutable.value.copy(buffering = true, revision = revision, failed = false)
            else AudioSnapshot(revision = revision)
        audioMutable.value = waiting
        mutable.update {
            it.copy(
                queue = items,
                queueKeys = queueKeys,
                history = history,
                manualQueueCount = manualCount,
                queueContext = context,
                queueIndex = index,
                buffering = true,
                audio = waiting,
                lyrics = if (recovering) it.lyrics else LyricDocument(),
                error = null,
            )
        }
        val session = state.value.session ?: return
        val item = items[index]
        // Fetch/cache lyrics independently of audio startup, so opening the paused player
        // after queue restoration can load them without starting the stream.
        fetchLyrics(session, item, revision)
        playJob = scope.launch {
            try {
                player.play(media.source(repository, session, item), item.durationMs)
                if (positionMs > 0)
                    withContext(Dispatchers.IO) {
                        player.seek(
                            positionMs.coerceAtMost(item.durationMs.coerceAtLeast(positionMs))
                        )
                    }
                val sample =
                    withContext(Dispatchers.IO) { player.snapshot() }.copy(revision = revision)
                audioMutable.value = sample
                mutable.update { it.copy(buffering = false, audio = sample) }
                report("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(buffering = false, error = errorMapper(e)) }
            }
        }
    }

    private fun setPaused(paused: Boolean) {
        if (!paused && state.value.audio.failed) {
            play(
                state.value.queue,
                state.value.queueIndex,
                audioMutable.value.positionMs,
                recovering = true,
            )
            return
        }
        if (restoredPending) {
            if (!paused)
                play(state.value.queue, state.value.queueIndex, audioMutable.value.positionMs)
            return
        }
        if (!paused && completedRevision == revision && state.value.current != null) {
            play(state.value.queue, state.value.queueIndex)
            return
        }
        scope.launch {
            val sample =
                withContext(Dispatchers.IO) {
                        player.pause(paused)
                        player.snapshot()
                    }
                    .copy(revision = revision)
            audioMutable.value = sample
            mutable.update { it.copy(audio = sample) }
            report("/Progress")
        }
    }

    private fun toggle() {
        setPaused(!state.value.audio.paused)
    }

    private fun seek(ms: Long) {
        if (restoredPending) {
            revision++
            val sample =
                audioMutable.value.copy(
                    positionMs = ms.coerceIn(0, audioMutable.value.durationMs.coerceAtLeast(0)),
                    revision = revision,
                )
            audioMutable.value = sample
            mutable.update { it.copy(audio = sample) }
            persistPlaybackNow()
            return
        }
        revision++
        scope.launch {
            val sample =
                withContext(Dispatchers.IO) {
                        player.seek(ms.coerceAtLeast(0))
                        player.snapshot()
                    }
                    .copy(revision = revision)
            audioMutable.value = sample
            mutable.update { it.copy(audio = audioMutable.value) }
            report("/Progress")
        }
    }

    private fun nextIndex(s: BellState, automatic: Boolean, usePlan: Boolean = true): Int? =
        when {
            automatic && s.repeatOne -> s.queueIndex.takeIf { it in s.queue.indices }

            s.queueIndex + 1 < s.queue.size -> s.queueIndex + 1
            s.repeat -> 0.takeIf { s.queue.isNotEmpty() }
            else -> null
        }

    private fun next(automatic: Boolean = false) {
        val s = state.value
        val index =
            nextIndex(s, automatic)
                ?: run {
                    if (automatic && s.autoPlay && !s.repeat) {
                        awaitingContinuation = true
                        extendQueue()
                        return
                    }
                    if (automatic) {
                        player.pause(true)
                        report("/Stopped")
                        persistPlaybackNow()
                    }
                    return
                }
        play(s.queue, index)
    }

    private fun previous() {
        val s = state.value
        if (audioMutable.value.positionMs > 3000) seek(0)
        else play(s.queue, (s.queueIndex - 1).coerceAtLeast(0))
    }

    internal fun miniSwipeIndex(forward: Boolean): Int? {
        val s = state.value
        if (!forward) return (s.queueIndex - 1).takeIf { it in s.queue.indices }
        val usePlan = plannedKey == planKey(s) && plannedNextId != s.current?.id
        return nextIndex(s, false, usePlan)?.takeIf { it != s.queueIndex }
    }

    private fun shuffle() {
        val s = state.value
        val start = (s.queueIndex + 1 + s.manualQueueCount).coerceIn(0, s.queue.size)
        val entries = s.queue.drop(start).zip(s.queueKeys.drop(start))
        val reordered =
            if (!s.shuffle) {
                unshuffledKeys = entries.map { it.second }
                entries.shuffled().let {
                    if (it.size > 1 && it == entries) it.drop(1) + it.first() else it
                }
            } else
                entries.sortedBy {
                    unshuffledKeys.indexOf(it.second).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
        invalidatePlan()
        mutable.update {
            it.copy(
                shuffle = !s.shuffle,
                queue = s.queue.take(start) + reordered.map { entry -> entry.first },
                queueKeys = s.queueKeys.take(start) + reordered.map { entry -> entry.second },
            )
        }
        persistPlaybackNow()
    }

    private fun repeat() {
        mutable.update {
            when {
                !it.repeat -> it.copy(repeat = true, repeatOne = false)
                !it.repeatOne -> it.copy(repeat = true, repeatOne = true)
                else -> it.copy(repeat = false, repeatOne = false)
            }
        }
        invalidatePlan()
        persistPlaybackNow()
    }

    private fun enqueue(item: Item) = enqueueMany(listOf(item))

    private fun enqueueMany(items: List<Item>, next: Boolean = false) {
        if (items.isEmpty()) return
        val s = state.value
        if (s.current == null) {
            play(items)
            return
        }
        val position =
            (s.queueIndex + 1 + (if (next) 0 else s.manualQueueCount)).coerceIn(0, s.queue.size)
        val newKeys = keys(items.size)
        mutable.update {
            it.copy(
                queue = s.queue.take(position) + items + s.queue.drop(position),
                queueKeys = s.queueKeys.take(position) + newKeys + s.queueKeys.drop(position),
                manualQueueCount = s.manualQueueCount + items.size,
            )
        }
        nextNoticeMutable.value =
            (kotlin.math.abs(nextNoticeMutable.value) + 1) * (if (next) 1 else -1)
        invalidatePlan()
        persistPlaybackNow()
    }

    private fun clearQueue() {
        val s = state.value
        val from = s.queueIndex + 1
        val to = (from + s.manualQueueCount).coerceAtMost(s.queue.size)
        if (from < 0 || from > to) return
        mutable.update {
            it.copy(
                queue = s.queue.take(from) + s.queue.drop(to),
                queueKeys = s.queueKeys.take(from) + s.queueKeys.drop(to),
                manualQueueCount = 0,
            )
        }
        invalidatePlan()
        persistPlaybackNow()
    }

    private fun moveQueue(fromKey: Long, toKey: Long) {
        val s = state.value
        val from = s.queueKeys.indexOf(fromKey)
        val to = s.queueKeys.indexOf(toKey)
        if (from <= s.queueIndex || to <= s.queueIndex || from < 0 || to < 0 || from == to) return
        val fromManual = from <= s.queueIndex + s.manualQueueCount
        val toManual = to <= s.queueIndex + s.manualQueueCount
        val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val entryKeys = s.queueKeys.toMutableList().apply { add(to, removeAt(from)) }
        val count =
            s.manualQueueCount +
                (if (!fromManual && toManual) 1 else if (fromManual && !toManual) -1 else 0)
        mutable.update {
            it.copy(queue = queue, queueKeys = entryKeys, manualQueueCount = count.coerceAtLeast(0))
        }
        invalidatePlan()
        persistPlaybackNow()
    }

    private fun playHistory(key: Long) {
        val s = state.value
        val entry = s.history.firstOrNull { it.key == key } ?: return
        val remaining = s.queue.drop(s.queueIndex + 1)
        play(listOf(entry.item) + remaining)
        mutable.update { it.copy(manualQueueCount = s.manualQueueCount) }
    }

    private fun remove(index: Int) {
        val s = state.value
        if (index !in s.queue.indices) return
        val q = s.queue.toMutableList().apply { removeAt(index) }
        val entryKeys = s.queueKeys.toMutableList().apply { removeAt(index) }
        if (index == s.queueIndex) {
            if (q.isEmpty()) {
                player.stop()
                mutable.update {
                    it.copy(
                        queue = emptyList(),
                        queueKeys = emptyList(),
                        queueIndex = -1,
                        manualQueueCount = 0,
                    )
                }
            } else {
                play(q, index.coerceAtMost(q.lastIndex))
                mutable.update { it.copy(queueKeys = entryKeys) }
            }
        } else
            mutable.update {
                it.copy(
                    queue = q,
                    queueKeys = entryKeys,
                    queueIndex = if (index < s.queueIndex) s.queueIndex - 1 else s.queueIndex,
                    manualQueueCount =
                        (s.manualQueueCount -
                                if (
                                    index > s.queueIndex &&
                                        index <= s.queueIndex + s.manualQueueCount
                                )
                                    1
                                else 0)
                            .coerceAtLeast(0),
                )
            }
        invalidatePlan()
        persistPlaybackNow()
    }

    private fun extendQueue() {
        val s = state.value
        val session = s.session ?: return
        val current = s.current ?: return
        if (
            !s.autoPlay ||
                s.repeat ||
                s.queue.size - s.queueIndex > 4 ||
                continuationJob?.isActive == true
        )
            return
        val key = s.queueKeys.getOrNull(s.queueIndex) to s.queueKeys.lastOrNull()
        if (key == continuationKey) return
        continuationKey = key
        continuationJob = scope.launch {
            val candidates = optional(emptyList<Item>()) { repository.mix(session, current.id) }
            val latest = state.value
            if (
                latest.session != session ||
                    !latest.autoPlay ||
                    latest.queueKeys.lastOrNull() != key.second
            )
                return@launch
            val existing = latest.queue.map { it.id }.toSet()
            val added = candidates.filter { it.id !in existing }.distinctBy { it.id }
            if (added.isNotEmpty()) {
                val addedKeys = keys(added.size)
                mutable.update {
                    it.copy(queue = it.queue + added, queueKeys = it.queueKeys + addedKeys)
                }
                if (awaitingContinuation) {
                    awaitingContinuation = false
                    play(state.value.queue, latest.queueIndex + 1)
                }
            } else if (awaitingContinuation) {
                awaitingContinuation = false
                player.pause(true)
            }
        }
    }

    private fun favorite(item: Item) = action {
        albumPages.clear()
        val favorite = !item.userData.favorite
        repository.favorite(requireNotNull(state.value.session), item, favorite)
        fun List<Item>.updated() = map {
            if (it.id == item.id) it.copy(userData = it.userData.copy(favorite = favorite)) else it
        }
        mutable.update {
            it.copy(
                songs = it.songs.updated(),
                albums = it.albums.updated(),
                playlists = it.playlists.updated(),
                queue = it.queue.updated(),
                albumSongs = it.albumSongs.updated(),
                results = it.results.updated(),
                pins = it.pins.updated(),
                latest = it.latest.updated(),
                recentlyPlayed = it.recentlyPlayed.updated(),
                recommendations = it.recommendations.updated(),
                searchHistory = it.searchHistory.updated(),
                artists = it.artists.updated(),
                artistAlbums = it.artistAlbums.updated(),
                album =
                    it.album?.let { a ->
                        if (a.id == item.id) a.copy(userData = a.userData.copy(favorite = favorite))
                        else a
                    },
            )
        }
    }

    private fun report(event: String) {
        val s = state.value
        val session = s.session ?: return
        val item = s.current ?: return
        val playback = audioMutable.value
        if (event.isEmpty()) {
            val album = state.value.albums.firstOrNull { it.id == item.albumId }
            if (album != null)
                mutable.update {
                    it.copy(
                        recentlyPlayed =
                            (listOf(album) +
                                    it.recentlyPlayed.filterNot { old -> old.id == album.id })
                                .take(24)
                    )
                }
        }
        scope.launch {
            try {
                repository.report(session, event, item, playback.positionMs, playback.paused)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun close() {
        runBlocking(Dispatchers.IO) { persistPlayback() }
        scope.cancel()
        player.close()
        media.close()
    }
}
