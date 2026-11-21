// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.screen.player.composables.DesktopMeshAlbum
import com.mocharealm.bell.ui.screen.player.mesh.AlbumTextureProcessor
import com.mocharealm.bell.ui.screen.player.mesh.BHPMesh
import com.mocharealm.bell.ui.screen.player.mesh.CONTROL_POINT_PRESETS
import com.mocharealm.bell.ui.screen.player.mesh.DesktopMeshBitmap
import com.mocharealm.bell.ui.screen.player.mesh.ShaderSource
import com.mocharealm.bell.ui.screen.player.mesh.runMeshFrames
import com.mocharealm.bell.ui.utils.rememberMusicSpectrum
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import org.jetbrains.skia.*

@Composable
internal actual fun MusicMeshBackground(
    model: BellStore,
    state: BellState,
    item: Item,
    modifier: Modifier,
) {
    val source = model.artwork(item, 600)
    val album by
        produceState<DesktopMeshAlbum?>(null, source) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        val bytes =
                            source?.let { model.repository.artworkBytes(it) }
                                ?: return@withContext null
                        val bitmap =
                            ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext null
                        val processed = AlbumTextureProcessor.process(DesktopMeshBitmap(bitmap))
                        val encoded =
                            java.io
                                .ByteArrayOutputStream()
                                .also { ImageIO.write(processed.image, "png", it) }
                                .toByteArray()
                        val image = Image.makeFromEncoded(encoded)
                        val mesh =
                            BHPMesh().apply {
                                resetSubdivision(20)
                                configureFromPreset(CONTROL_POINT_PRESETS.random())
                            }
                        val vertices = checkNotNull(mesh.buffer)
                        val count = vertices.capacity() / 7
                        val positions = FloatArray(count * 2)
                        val uv = FloatArray(count * 2)
                        for (i in 0 until count) {
                            positions[i * 2] = vertices[i * 7]
                            positions[i * 2 + 1] = vertices[i * 7 + 1]
                            uv[i * 2] = vertices[i * 7 + 5]
                            uv[i * 2 + 1] = vertices[i * 7 + 6]
                        }
                        val index = mesh.generateIndexBuffer()
                        val indices = ShortArray(mesh.indices) { index[it].toShort() }
                        val effect = RuntimeEffect.makeForShader(ShaderSource.MESH_SHADER)
                        val builder =
                            RuntimeShaderBuilder(effect).apply {
                                child(
                                    "albumTexture",
                                    image.makeShader(
                                        FilterTileMode.MIRROR,
                                        FilterTileMode.MIRROR,
                                        SamplingMode.LINEAR,
                                    ),
                                )
                                uniform("textureSize", 32f, 32f)
                                uniform("audioResponse", 1f, 1f, 1f)
                            }
                        DesktopMeshAlbum(builder, image, positions, uv, indices)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
        }
    val spectrum =
        rememberMusicSpectrum(model.player, !state.audio.paused, item.id to state.audio.revision)
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.audio.paused) {
        var previous = 0L
        if (!state.audio.paused)
            runMeshFrames(33_333_333L, { true }) { now ->
                if (previous != 0L) elapsed += now - previous
                previous = now
            }
    }
    Crossfade(album, modifier.fillMaxSize(), animationSpec = tween(850)) { current ->
        val positions = remember(current) { current?.positions?.copyOf() }
        var width = 0f
        var height = 0f
        Canvas(Modifier.fillMaxSize()) {
            drawRect(androidx.compose.ui.graphics.Color(0xFF252527))
            if (current != null && positions != null) {
                if (width != size.width || height != size.height) {
                    width = size.width
                    height = size.height
                    val aspect = width / height
                    val sx = if (aspect > 1) 1f else 1f / aspect
                    val sy = if (aspect > 1) aspect else 1f
                    for (i in positions.indices step 2) {
                        positions[i] = (current.positions[i] * sx + 1) * width * .5f
                        positions[i + 1] = (1 - current.positions[i + 1] * sy) * height * .5f
                    }
                }
                current.builder.uniform("time", elapsed / 1e9f * .25f)
                val feedback = spectrum.value
                val band = feedback.low * .9f + feedback.mid * .1f
                current.builder.uniform(
                    "audioResponse",
                    1f + band * band * .04f + feedback.pulse * .62f,
                    1f + feedback.low * .076f,
                    1f + feedback.high * .166f,
                )
                current.builder.makeShader().use { shader ->
                    Paint().use { paint ->
                        paint.shader = shader
                        drawIntoCanvas {
                            it.nativeCanvas.drawVertices(
                                VertexMode.TRIANGLES,
                                positions,
                                null,
                                current.uv,
                                current.indices,
                                BlendMode.SRC,
                                paint,
                            )
                        }
                    }
                }
            }
        }
    }
}
