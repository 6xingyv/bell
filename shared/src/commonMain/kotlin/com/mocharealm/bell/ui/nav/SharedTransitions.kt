// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.Image
import com.mocharealm.bell.domain.model.Session
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal val LocalMusicSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalMusicAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

internal data class ArtworkOrigin(val itemId: String, val token: String)

internal class ArtworkNavigation {
    var origin by mutableStateOf<ArtworkOrigin?>(null)
    val origins = mutableStateMapOf<String, ArtworkOrigin>()
    val containers = mutableMapOf<String, MusicContainerGeometry>()
}

internal val LocalArtworkNavigation = staticCompositionLocalOf<ArtworkNavigation?> { null }

internal data class ArtworkLink(val key: String, val open: () -> Unit)

@Composable
internal fun rememberArtworkLink(
    itemId: String,
    container: Boolean = true,
    open: () -> Unit,
): ArtworkLink {
    val token =
        rememberSaveable(itemId, container) {
            "${if(container)"container"else"artwork"}:${Random.nextLong()}"
        }
    val navigation = LocalArtworkNavigation.current
    val latestOpen = rememberUpdatedState(open)
    return remember(token, itemId, navigation) {
        ArtworkLink(token) {
            navigation?.let {
                val origin = ArtworkOrigin(itemId, token)
                it.origin = origin
                it.origins[itemId] = origin
            }
            latestOpen.value()
        }
    }
}

internal class MusicContainerGeometry {
    var sourceBounds by mutableStateOf<Rect?>(null)
    var destinationBounds by mutableStateOf<Rect?>(null)
    var sourceCorners by mutableStateOf(musicCorners(9.dp))
    var destinationCorners by mutableStateOf(PlayerWindowCorners())

    fun progress(bounds: Rect): Float {
        val from = sourceBounds ?: return 1f
        val to = destinationBounds ?: return 0f
        val dw = to.width - from.width
        val dh = to.height - from.height
        val distance = dw * dw + dh * dh
        return if (distance < 1f) 1f
        else
            (((bounds.width - from.width) * dw + (bounds.height - from.height) * dh) / distance)
                .coerceIn(0f, 1f)
    }
}

internal fun musicCorners(radius: Dp) = PlayerWindowCorners(radius, radius, radius, radius)

private class MusicContainerClip(val geometry: MusicContainerGeometry) :
    SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val p = geometry.progress(bounds)
        val from = geometry.sourceCorners
        val to = geometry.destinationCorners
        fun corner(a: Dp, b: Dp) = a + (b - a) * p
        val corners =
            PlayerWindowCorners(
                corner(from.topLeft, to.topLeft),
                corner(from.topRight, to.topRight),
                corner(from.bottomRight, to.bottomRight),
                corner(from.bottomLeft, to.bottomLeft),
            )
        return Path().apply {
            addOutline(
                playerPanelShape(1f, corners).createOutline(bounds.size, layoutDirection, density)
            )
            translate(bounds.topLeft)
        }
    }
}

/** Container corners follow the live shared bounds, including reverse/interactive motion. */
@OptIn(ExperimentalDeferredTransitionApi::class)
@Composable
internal fun musicSharedContainer(
    key: String?,
    corners: PlayerWindowCorners,
    isDestination: Boolean = false,
): Modifier {
    if (key == null) return Modifier
    val scope = LocalMusicSharedScope.current ?: return Modifier
    val animated = LocalMusicAnimatedScope.current ?: return Modifier
    val navigation = LocalArtworkNavigation.current
    val geometry =
        remember(navigation, key) {
            navigation?.containers?.getOrPut(key) { MusicContainerGeometry() }
                ?: MusicContainerGeometry()
        }
    val screen = LocalPlayerMotion.current?.screenBounds
    SideEffect {
        if (isDestination) {
            geometry.destinationCorners = corners
            if (screen != null && !screen.isEmpty) geometry.destinationBounds = screen
        } else geometry.sourceCorners = corners
    }
    val clip = remember(geometry) { MusicContainerClip(geometry) }
    val config = remember {
        object : SharedTransitionScope.SharedContentConfig {
            override val permitTransformDuringDeferredTransition = true
        }
    }
    return with(scope) {
        Modifier.onGloballyPositioned {
                if (isDestination) {
                    if (geometry.destinationBounds == null || !scope.isTransitionActive)
                        geometry.destinationBounds = it.boundsInRoot()
                } else if (geometry.sourceBounds == null || !scope.isTransitionActive)
                    geometry.sourceBounds = it.boundsInRoot()
            }
            .sharedBounds(
                rememberSharedContentState(key, config),
                animated,
                boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 420f) },
                resizeMode =
                    SharedTransitionScope.ResizeMode.scaleToBounds(
                        androidx.compose.ui.layout.ContentScale.FillWidth,
                        androidx.compose.ui.Alignment.TopStart,
                    ),
                clipInOverlayDuringTransition = clip,
                zIndexInOverlay = if (isDestination) 1f else 0f,
            )
    }
}

@Composable expect fun musicSharedArtwork(key: String?): Modifier

// Artwork retains its own rounded clip, but may cross its container's moving edge.
internal object MusicArtworkOverlayClip : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path? = null
}

internal data class PlayerWindowCorners(
    val topLeft: Dp = 0.dp,
    val topRight: Dp = 0.dp,
    val bottomRight: Dp = 0.dp,
    val bottomLeft: Dp = 0.dp,
)

@Composable internal expect fun playerWindowCorners(): PlayerWindowCorners

internal expect fun playerPanelShape(progress: Float, corners: PlayerWindowCorners): Shape

internal enum class PlayerArtworkRole {
    Mini,
    Expanded,
}

internal data class DecodedPlayerArtwork(val session: Session, val itemId: String, val image: Image)

internal val LocalPlayerArtworkRole = staticCompositionLocalOf<PlayerArtworkRole?> { null }
internal val LocalPlayerMotion = staticCompositionLocalOf<PlayerMotion?> { null }

internal interface MusicPlayerBackdropSource

internal class MusicPlayerBackdropHost {
    var source by mutableStateOf<MusicPlayerBackdropSource?>(null)
}

internal val LocalMusicPlayerBackdropHost =
    staticCompositionLocalOf<MusicPlayerBackdropHost?> { null }

@Composable
internal expect fun MusicPlayerGlassLayer(modifier: Modifier, shape: Shape, halfResolution: Boolean)

/** The reference's normalized spring drives geometry, composition and the independent cover. */
internal class PlayerMotion(private val scope: CoroutineScope) {
    val animation = Animatable(0f).apply { updateBounds(0f, 1f) }

    private sealed interface Input {
        data class Drag(val progress: Float) : Input

        data class Settle(val expanded: Boolean, val velocity: Float) : Input
    }

    private val input = Channel<Input>(Channel.UNLIMITED)
    private var settling: Job? = null
    private var fingerProgress = 0f
    private var dragOriginExpanded = false
    var dragging by mutableStateOf(false)
        private set

    var expanded by mutableStateOf(false)
        private set

    var miniBounds by mutableStateOf<Rect?>(null)
    var thumbnailInMini by mutableStateOf<Rect?>(null)
        private set
    var miniContentCoordinates: LayoutCoordinates? = null
    var albumArtBounds by mutableStateOf<Rect?>(null)
    var miniRadius by mutableStateOf(5.dp)
    var albumArtRadius by mutableStateOf(9.dp)
    var screenBounds by mutableStateOf(Rect.Zero)
    var navigationCoordinates: LayoutCoordinates? = null
    var miniGlassSize = IntSize.Zero
    private var miniGlassVisualBounds by mutableStateOf<Rect?>(null)
    private var openingGlassOffsets by mutableStateOf(Rect.Zero)
    private var returningToMiniAt by mutableFloatStateOf(0f)
    private var returnGlassOffsets by mutableStateOf(Rect.Zero)

    fun updateMiniBounds(coordinates: LayoutCoordinates) {
        val navigation = navigationCoordinates?.takeIf { it.isAttached } ?: return
        // The navigation layer scales during the transition. Measure the dock before that scale.
        miniBounds = navigation.localBoundingBoxOf(coordinates, clipBounds = false)
            .translate(screenBounds.topLeft)
    }

    fun updateThumbnailBounds(coordinates: LayoutCoordinates) {
        val content = miniContentCoordinates?.takeIf { it.isAttached } ?: return
        thumbnailInMini = content.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    fun updateMiniGlassVisualBounds(bounds: Rect) {
        miniGlassVisualBounds = bounds
    }

    private fun miniGlassOffsets(): Rect {
        val visual = miniGlassVisualBounds ?: return Rect.Zero
        val size = miniGlassSize
        if (size == IntSize.Zero) return Rect.Zero
        return Rect(visual.left, visual.top, visual.right - size.width, visual.bottom - size.height)
    }

    fun transitionGlassOffsets(progress: Float): Rect {
        val weight = 1f - smooth(progress / .18f)
        if (weight == 0f) return Rect.Zero
        val returning = returningToMiniAt
        if (returning <= 0f) return openingGlassOffsets.scaled(weight)
        val handoff = smooth(progress / returning)
        val live = miniGlassOffsets().scaled(weight)
        val startWeight = 1f - smooth(returning / .18f)
        val start = if (weight < startWeight) returnGlassOffsets.scaled(weight / startWeight)
            else returnGlassOffsets
        return Rect(
            blend(live.left, start.left, handoff),
            blend(live.top, start.top, handoff),
            blend(live.right, start.right, handoff),
            blend(live.bottom, start.bottom, handoff),
        )
    }

    private fun Rect.scaled(weight: Float): Rect =
        Rect(left * weight, top * weight, right * weight, bottom * weight)
    val visible by derivedStateOf(structuralEqualityPolicy()) { expanded || dragging || progress > 0f }
    val inProgress by derivedStateOf(structuralEqualityPolicy()) { progress > 0f && progress < 1f }
    val pastMidpoint by derivedStateOf(structuralEqualityPolicy()) { progress > .5f }
    val showMiniTransitionContent by derivedStateOf(structuralEqualityPolicy()) { progress < .18f }
    var miniArtwork by mutableStateOf<DecodedPlayerArtwork?>(null)
    var expandedArtwork by mutableStateOf<DecodedPlayerArtwork?>(null)

    val progress
        get() = animation.value

    init {
        scope.launch {
            for (event in input) when (event) {
                is Input.Drag -> {
                    settling?.cancelAndJoin()
                    settling = null
                    animation.snapTo(event.progress)
                }
                is Input.Settle -> {
                    settling?.cancelAndJoin()
                    settling = scope.launch {
                        val height = screenBounds.height.coerceAtLeast(1f)
                        animation.animateTo(
                            if (event.expanded) 1f else 0f,
                            spring(
                                dampingRatio = 1f,
                                stiffness = 300f,
                                visibilityThreshold = .5f / height,
                            ),
                            initialVelocity = -event.velocity / height,
                        )
                    }
                }
            }
        }
    }

    fun settle(value: Boolean, velocity: Float = 0f) {
        if (value && progress == 0f && !dragging) {
            openingGlassOffsets = miniGlassOffsets()
            returningToMiniAt = 0f
        } else if (!value) {
            returnGlassOffsets = transitionGlassOffsets(progress)
            returningToMiniAt = minOf(progress, .18f)
        }
        expanded = value
        dragging = false
        input.trySend(Input.Settle(value, velocity))
    }

    fun begin() {
        if (progress == 0f) {
            openingGlassOffsets = miniGlassOffsets()
            returningToMiniAt = 0f
        }
        if (progress >= .18f) prepareGlassReturn()
        dragOriginExpanded = expanded || progress > .5f
        fingerProgress = progress
        dragging = true
        input.trySend(Input.Drag(fingerProgress))
    }

    fun drag(delta: Float, height: Float) {
        fingerProgress = (fingerProgress - delta / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (fingerProgress >= .18f) prepareGlassReturn()
        input.trySend(Input.Drag(fingerProgress))
    }

    private fun prepareGlassReturn() {
        returnGlassOffsets = Rect.Zero
        returningToMiniAt = .18f
    }

    fun end(velocity: Float, velocityThreshold: Float) {
        settle(
            when {
                velocity < -velocityThreshold -> true
                velocity > velocityThreshold -> false
                else ->
                    if (dragOriginExpanded) fingerProgress > .75f
                    else
                        fingerProgress >
                            ((miniBounds?.height ?: screenBounds.height * .06f) * 1.5f /
                                    screenBounds.height.coerceAtLeast(1f))
                                .coerceIn(.06f, .15f)
            },
            velocity,
        )
    }

    fun cancel() {
        settle(expanded)
    }
}

private fun blend(a: Float, b: Float, p: Float) = a + (b - a) * p

private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

// Above the dock, this is a full-height sheet translated by the finger. Only the
// final part of the spring changes its container into the mini-player capsule.
internal fun PlayerMotion.panelBounds(): Rect {
    val small = miniBounds ?: screenBounds
    val p = progress
    val terminal = smooth(p / .45f)
    val left = blend(small.left, screenBounds.left, terminal)
    val top = blend(small.top, screenBounds.top, p)
    val width = blend(small.width, screenBounds.width, terminal)
    val height = blend(small.height, screenBounds.height, terminal)
    val glass = transitionGlassOffsets(p)
    return Rect(left + glass.left, top + glass.top, left + width + glass.right, top + height + glass.bottom)
}

internal fun PlayerMotion.coverBounds(): Rect? {
    val thumbnail = thumbnailInMini ?: return null
    val mini = miniBounds ?: return null
    val glass = transitionGlassOffsets(progress)
    val scaleX = (mini.width + glass.right - glass.left) / mini.width.coerceAtLeast(1f)
    val scaleY = (mini.height + glass.bottom - glass.top) / mini.height.coerceAtLeast(1f)
    val small = Rect(
        mini.left + glass.left + thumbnail.left * scaleX,
        mini.top + glass.top + thumbnail.top * scaleY,
        mini.left + glass.left + thumbnail.right * scaleX,
        mini.top + glass.top + thumbnail.bottom * scaleY,
    )
    val large = albumArtBounds ?: return null
    fun sheetCover(p: Float): Rect {
        val amount = smooth(p / .45f)
        val left = blend(mini.left, screenBounds.left, amount)
        val top = blend(mini.top, screenBounds.top, p)
        val scale =
            blend(mini.width, screenBounds.width, amount) / screenBounds.width.coerceAtLeast(1f)
        return Rect(
            left + (large.left - screenBounds.left) * scale,
            top + (large.top - screenBounds.top) * scale,
            left + (large.right - screenBounds.left) * scale,
            top + (large.bottom - screenBounds.top) * scale,
        )
    }
    // The artwork belongs to the dragged sheet until the terminal dock morph.
    if (progress >= .45f) return sheetCover(progress)
    val target = sheetCover(.45f)
    val p = smooth(progress / .45f)
    return Rect(
        blend(small.left, target.left, p),
        blend(small.top, target.top, p),
        blend(small.right, target.right, p),
        blend(small.bottom, target.bottom, p),
    )
}

internal fun Modifier.playerDrag(
    motion: PlayerMotion,
    height: Float,
    velocityThreshold: Float,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    this.onGloballyPositioned { coordinates = it }
        .pointerInput(motion, height, velocityThreshold) {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = {
                    tracker.resetTracking()
                    motion.begin()
                },
                onVerticalDrag = { change, delta ->
                    val layout = coordinates?.takeIf { it.isAttached }
                    val rootPosition = layout?.localToRoot(change.position) ?: change.position
                    val scale =
                        layout?.let {
                            (it.localToRoot(Offset(0f, 1f)) - it.localToRoot(Offset.Zero)).y
                        } ?: 1f
                    tracker.addPosition(change.uptimeMillis, rootPosition)
                    change.consume()
                    motion.drag(delta * scale, height)
                },
                onDragEnd = { motion.end(tracker.calculateVelocity().y, velocityThreshold) },
                onDragCancel = motion::cancel,
            )
        }
}
