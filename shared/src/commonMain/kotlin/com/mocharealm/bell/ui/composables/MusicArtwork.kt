// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellState
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.nav.LocalPlayerArtworkRole
import com.mocharealm.bell.ui.nav.LocalPlayerMotion
import com.mocharealm.bell.ui.nav.PlayerArtworkRole
import com.mocharealm.bell.ui.nav.musicCorners
import com.mocharealm.bell.ui.nav.musicSharedArtwork
import com.mocharealm.bell.ui.nav.musicSharedContainer
import com.mocharealm.bell.ui.utils.LocalMusicOffline
import com.mocharealm.bell.ui.utils.formatBellString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicArtwork(
    model: BellStore,
    state: BellState,
    item: Item?,
    modifier: Modifier,
    radius: Dp = 9.dp,
    sharedKey: String? = null,
    shadowElevation: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val offline = LocalMusicOffline.current
    val text_artwork_description = stringResource(Res.string.artwork_description)

    if (sharedKey?.startsWith("container:") == true) {
        Box(modifier.then(musicSharedContainer(sharedKey, musicCorners(radius)))) {
            MusicArtwork(
                model,
                state,
                item,
                Modifier.fillMaxSize(),
                radius,
                sharedKey = "cover:$sharedKey",
                shadowElevation = shadowElevation,
                contentScale = contentScale,
            )
        }
        return
    }
    val dark = LocalMusicDark.current
    val motion = LocalPlayerMotion.current
    val role = LocalPlayerArtworkRole.current.takeIf { sharedKey?.startsWith("playing:") == true }
    val playerArtwork =
        if (role != null && motion != null)
            Modifier.onGloballyPositioned { coordinates ->
                    if (role == PlayerArtworkRole.Mini && motion.progress == 0f) {
                        motion.thumbnailBounds = coordinates.boundsInRoot()
                        motion.miniRadius = radius
                    } else if (role == PlayerArtworkRole.Expanded) {
                        motion.albumArtBounds = coordinates.boundsInRoot()
                        motion.albumArtRadius = radius
                    }
                }
                .drawWithContent {
                    if (
                        if (role == PlayerArtworkRole.Mini) motion.progress == 0f
                        else motion.progress == 1f
                    )
                        drawContent()
                }
        else Modifier
    val artItem =
        when {
            item?.id?.startsWith("jellify-album:") == true ->
                state.songs.firstOrNull { it.album == item.name }
            item?.id?.startsWith("jellify-mix:") == true ->
                state.mixes.firstOrNull { it.seed.id == item.id.removePrefix("jellify-mix:") }?.seed
            else -> item
        }
    val source =
        state.session?.let { s -> artItem?.let { model.artwork(it, offlineOnly = offline) } }
    val ratioHint =
        artItem?.primaryImageAspectRatio
            ?: state.albums.firstOrNull { it.id == artItem?.albumId }?.primaryImageAspectRatio
    val artist = artItem?.type == "MusicArtist"
    var ratio by
        remember(source, ratioHint) {
            mutableFloatStateOf(ratioHint?.takeIf { it.isFinite() && it > 0f } ?: 1f)
        }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val (artWidth, artHeight) =
            if (artist || contentScale == ContentScale.Crop) maxWidth to maxHeight
            else fittedMusicArtwork(maxWidth, maxHeight, ratio)
        Box(
            Modifier.size(artWidth, artHeight)
                .then(playerArtwork)
                .then(musicSharedArtwork(sharedKey.takeIf { role == null }))
                .shadow(shadowElevation, musicShape(radius), clip = false)
                .clip(musicShape(radius))
                .background(if (dark) Color.White.copy(alpha = .12f) else Color(0xFFEEEEEF))
                .border(
                    .5.dp,
                    if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .05f),
                    musicShape(radius),
                ),
            contentAlignment = Alignment.Center,
        ) {
            MusicIcon(
                if (artist) "music.microphone" else "note",
                if (dark) Color.White.copy(alpha = .55f) else Color(0xFFB6B6BA),
                minOf(artWidth, artHeight) * .38f,
            )
            AsyncImage(
                source,
                item?.let { formatBellString(text_artwork_description, it.name) },
                Modifier.fillMaxSize(),
                contentScale = if (artist) ContentScale.Crop else contentScale,
                onSuccess = { result ->
                    val image = result.result.image
                    if (!artist && image.width > 0 && image.height > 0)
                        ratio = image.width.toFloat() / image.height
                },
            )
        }
    }
}

internal fun fittedMusicArtwork(width: Dp, height: Dp, aspectRatio: Float): Pair<Dp, Dp> {
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val fittedWidth = minOf(width, height * ratio)
    return fittedWidth to fittedWidth / ratio
}

expect fun musicShape(radius: Dp): Shape

@Composable
internal fun rememberMusicArtworkColor(
    model: BellStore,
    state: BellState,
    item: Item,
): State<Color> {
    val offline = LocalMusicOffline.current
    val artItem =
        when {
            item.id.startsWith("jellify-album:") ->
                state.songs.firstOrNull { it.album == item.name }
            item.id.startsWith("jellify-mix:") ->
                state.mixes.firstOrNull { it.seed.id == item.id.removePrefix("jellify-mix:") }?.seed
            else -> item
        }
    val url = state.session?.let { s -> artItem?.let { model.artwork(it, 64, offline) } }
    val cache = LocalMusicArtworkColors.current
    return produceState(cache[url] ?: Color(0xFF48484A), url) {
        value =
            cache[url]
                ?: try {
                    if (url == null) Color(0xFF48484A)
                    else
                        withContext(Dispatchers.IO) {
                                dominantMusicColor(
                                    artworkPixels(model.repository.artworkBytes(url))
                                )
                            }
                            .also { cache[url] = it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Color(0xFF48484A)
                }
    }
}

@Composable
fun AmbientArtwork(
    model: BellStore,
    state: BellState,
    item: Item,
    player: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (player) {
        MusicMeshBackground(model, state, item, modifier)
        return
    }
    val base by rememberMusicArtworkColor(model, state, item)
    val top by animateColorAsState(if (player) lerp(base, Color.White, .18f) else base, tween(650))
    val bottom by
        animateColorAsState(
            if (player) lerp(base, Color.Black, .77f) else lerp(base, Color.Black, .2f),
            tween(650),
        )
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
}

private fun dominantMusicColor(pixels: IntArray): Color {
    if (pixels.isEmpty()) return Color(0xFF48484A)
    data class Bucket(
        var weight: Float = 0f,
        var r: Float = 0f,
        var g: Float = 0f,
        var b: Float = 0f,
    )
    val buckets = mutableMapOf<Int, Bucket>()
    var neutralWeight = 0f
    var neutralLuminance = 0f
    var chromaticWeight = 0f
    pixels.forEach { argb ->
        val alpha = (argb ushr 24 and 255) / 255f
        if (alpha < .5f) return@forEach
        val r = (argb ushr 16 and 255) / 255f
        val g = (argb ushr 8 and 255) / 255f
        val b = (argb and 255) / 255f
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val saturation = (hi - lo) / hi.coerceAtLeast(.001f)
        val luminance = .2126f * r + .7152f * g + .0722f * b
        if (saturation <= .12f || hi < .12f) {
            neutralWeight += alpha
            neutralLuminance += luminance * alpha
        } else chromaticWeight += alpha
        if (hi in 0.12f..0.95f && saturation > .12f) {
            val w = .4f + saturation
            val key = ((r * 7).toInt() shl 6) or ((g * 7).toInt() shl 3) or (b * 7).toInt()
            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.weight += w
            bucket.r += r * w
            bucket.g += g * w
            bucket.b += b * w
        }
    }
    if (neutralWeight > 0f && (chromaticWeight < neutralWeight * .15f || buckets.isEmpty())) {
        val gray = (neutralLuminance / neutralWeight).coerceIn(.18f, .5f)
        return Color(gray, gray, gray)
    }
    val selected = buckets.values.maxByOrNull { it.weight } ?: return Color(0xFF48484A)
    val r = selected.r / selected.weight
    val g = selected.g / selected.weight
    val b = selected.b / selected.weight
    val hi = maxOf(r, g, b)
    val scale = hi.coerceIn(.3f, .8f) / hi.coerceAtLeast(.001f)
    return Color(
        (r * scale).coerceIn(0f, 1f),
        (g * scale).coerceIn(0f, 1f),
        (b * scale).coerceIn(0f, 1f),
    )
}

@Composable
internal expect fun MusicMeshBackground(
    model: BellStore,
    state: BellState,
    item: Item,
    modifier: Modifier,
)
