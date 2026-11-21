// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.util.LruCache
import com.mocharealm.bell.R
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.MainActivity
import io.ktor.client.request.get
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.koin.core.context.GlobalContext

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model: BellStore
        get() = GlobalContext.get().get()

    private lateinit var session: MediaSession
    private lateinit var focus: AudioFocusRequest
    private var interrupted = false
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var artwork: Bitmap? = null
    private val artworkCache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private lateinit var wake: PowerManager.WakeLock
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                model.accept(BellIntent.SetPaused(true))
            }
        }

    override fun onCreate() {
        super.onCreate()
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bell:playback")
                .apply { setReferenceCounted(false) }
        registerReceiver(
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            RECEIVER_NOT_EXPORTED,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "playback",
                    getString(R.string.playback_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        session =
            MediaSession(this, getString(R.string.app_name)).apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() {
                            model.accept(BellIntent.SetPaused(false))
                        }

                        override fun onPause() {
                            model.accept(BellIntent.SetPaused(true))
                        }

                        override fun onSkipToNext() {
                            model.accept(BellIntent.Next())
                        }

                        override fun onSkipToPrevious() {
                            model.accept(BellIntent.Previous)
                        }

                        override fun onSeekTo(pos: Long) {
                            model.accept(BellIntent.Seek(pos))
                        }

                        override fun onStop() {
                            model.accept(BellIntent.SetPaused(true))
                            stopSelf()
                        }
                    }
                )
                isActive = true
            }
        val audio = getSystemService(AudioManager::class.java)
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            interrupted = !model.state.value.audio.paused
                            model.accept(BellIntent.SetPaused(true))
                        }
                        AudioManager.AUDIOFOCUS_GAIN ->
                            if (interrupted) {
                                interrupted = false
                                model.accept(BellIntent.SetPaused(false))
                            }
                    }
                }
                .build()
        startForeground(1, notification(BellState()))
        audio.requestAudioFocus(focus)
        scope.launch {
            var lastItem: Item? = null
            var lastPaused: Boolean? = null
            model.state.collectLatest { state ->
                if ((state.buffering || !state.audio.paused) && state.current != null) {
                    if (!wake.isHeld) wake.acquire(10 * 60_000L)
                } else if (wake.isHeld) wake.release()
                val item = state.current
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE or
                                PlaybackState.ACTION_SEEK_TO or
                                PlaybackState.ACTION_SKIP_TO_NEXT or
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackState.ACTION_STOP
                        )
                        .setState(
                            if (state.buffering || state.audio.buffering)
                                PlaybackState.STATE_BUFFERING
                            else if (state.audio.paused) PlaybackState.STATE_PAUSED
                            else PlaybackState.STATE_PLAYING,
                            model.playback.value.positionMs,
                            if (state.audio.paused || state.audio.buffering) 0f else 1f,
                        )
                        .build()
                )
                val imageKey =
                    state.session?.let { if (item != null) model.artwork(item, 512) else null }
                if (imageKey != artworkKey) {
                    artworkJob?.cancel()
                    artworkKey = imageKey
                    artwork = imageKey?.let(artworkCache::get)
                    updateMetadata(state)
                    getSystemService(NotificationManager::class.java).notify(1, notification(state))
                    if (imageKey != null && artwork == null)
                        artworkJob = scope.launch {
                            val bitmap =
                                withContext(Dispatchers.IO) {
                                    try {
                                        val bytes =
                                            if (imageKey.startsWith("file:"))
                                                java.io
                                                    .File(
                                                        java.net.URI(imageKey.substringBefore('#'))
                                                    )
                                                    .readBytes()
                                            else model.repository.artworkBytes(imageKey)
                                        val bounds =
                                            BitmapFactory.Options().apply {
                                                inJustDecodeBounds = true
                                            }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                        val options =
                                            BitmapFactory.Options().apply {
                                                inSampleSize = 1
                                                while (
                                                    bounds.outWidth / inSampleSize > 1024 ||
                                                        bounds.outHeight / inSampleSize > 1024
                                                ) inSampleSize *= 2
                                            }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            ensureActive()
                            if (artworkKey == imageKey && bitmap != null) {
                                artworkCache.put(imageKey, bitmap)
                                artwork = bitmap
                                val latest = model.state.value
                                updateMetadata(latest)
                                getSystemService(NotificationManager::class.java)
                                    .notify(1, notification(latest))
                            }
                        }
                }
                if (item != lastItem || state.audio.paused != lastPaused) {
                    lastItem = item
                    lastPaused = state.audio.paused
                    updateMetadata(state)
                    getSystemService(NotificationManager::class.java).notify(1, notification(state))
                }
                if (state.session == null) stopSelf()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        model.persistPlaybackNow()
        super.onTaskRemoved(rootIntent)
    }

    private fun updateMetadata(state: BellState) {
        val item = state.current
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item?.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item?.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item?.durationMs ?: 0)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                .build()
        )
    }

    private fun command(action: String, request: Int) =
        PendingIntent.getService(
            this,
            request,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(state: BellState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, "playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(artwork)
            .setContentTitle(state.current?.name ?: getString(R.string.app_name))
            .setContentText(state.current?.artist ?: getString(R.string.preparing_playback))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        getString(R.string.previous_track),
                        command("previous", 1),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        if (state.audio.paused) android.R.drawable.ic_media_play
                        else android.R.drawable.ic_media_pause,
                        if (state.audio.paused) getString(R.string.play)
                        else getString(R.string.pause),
                        command("toggle", 2),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        getString(R.string.next_track),
                        command("next", 3),
                    )
                    .build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "previous" -> model.accept(BellIntent.Previous)
            "next" -> model.accept(BellIntent.Next())
            "toggle" -> model.accept(BellIntent.Toggle)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        artworkCache.evictAll()
        session.release()
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)
        unregisterReceiver(noisy)
        if (wake.isHeld) wake.release()
        model.accept(BellIntent.SetPaused(true))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
