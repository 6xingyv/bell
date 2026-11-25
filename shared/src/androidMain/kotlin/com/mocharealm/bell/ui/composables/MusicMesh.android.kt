// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.playback.MusicSpectrum
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.screen.player.mesh.MeshAlbum
import com.mocharealm.bell.ui.screen.player.mesh.MeshGradientRenderer
import com.mocharealm.bell.ui.screen.player.mesh.runMeshFrames
import com.mocharealm.bell.ui.utils.rememberMusicSpectrum
import java.io.File
import java.net.URI
import kotlinx.coroutines.*

@Composable
internal actual fun MusicMeshBackground(
    model: BellStore,
    state: BellState,
    item: Item,
    modifier: Modifier,
) {
    val renderer = remember { MeshGradientRenderer().apply { audioStrength = 1f } }
    val layer = rememberGraphicsLayer()
    val source = model.artwork(item, 600)
    var frame by remember { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<MeshAlbum?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by
        remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val spectrum =
        rememberMusicSpectrum(
            model.player,
            !state.audio.paused && visible,
            item.id to state.audio.revision,
        )
    LaunchedEffect(source) {
        val prepared =
            withContext(Dispatchers.IO) {
                try {
                    val bytes =
                        if (source?.startsWith("file:") == true)
                            File(URI(source.substringBefore('#'))).readBytes()
                        else source?.let { model.repository.artworkBytes(it) }
                    val bitmap =
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            ?: return@withContext null
                    try {
                        MeshGradientRenderer.prepareAlbum(bitmap, 20)
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        if (prepared != null) {
            album = prepared
            renderer.setAlbum(prepared)
            frame++
        }
    }
    LaunchedEffect(album, state.audio.paused, visible) {
        renderer.isPlaying = !state.audio.paused && visible
        if (!renderer.isPlaying) renderer.spectrum = MusicSpectrum.Zero
        renderer.resetFrameClock()
        if (visible)
            runMeshFrames(33_333_333L, { renderer.needsAnimation }) { time ->
                renderer.spectrum = if (renderer.isPlaying) spectrum.value else MusicSpectrum.Zero
                renderer.advanceFrame(time)
                frame++
            }
        frame++
    }
    Canvas(modifier.fillMaxSize()) {
        frame
        if (renderer.hasAlbum) renderer.draw(this, layer, .5f)
        else drawRect(androidx.compose.ui.graphics.Color(0xFF252527))
    }
}
