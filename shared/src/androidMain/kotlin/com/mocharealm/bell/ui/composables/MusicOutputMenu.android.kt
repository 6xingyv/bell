// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MusicOutputMenu(
    model: BellStore,
    state: BellState,
    modifier: Modifier,
    centerHorizontally: Boolean,
    onGroups: () -> Unit,
) {
    val context = LocalContext.current
    val router = remember(context) { MediaRouter2.getInstance(context) }
    fun activeController() =
        router.controllers.lastOrNull { !it.isReleased && it.id != router.systemController.id }
            ?: router.systemController
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val permissions = remember {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    }
    var routingAllowed by remember {
        mutableStateOf(
            permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        )
    }
    val permissionRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            routingAllowed = permissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
    var routes by remember { mutableStateOf<List<MediaRoute2Info>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var routeVolume by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val deviceName =
        remember(context, expanded) { com.mocharealm.bell.data.platform.androidDeviceName(context) }
    val label = stringResource(Res.string.audio_output)
    val groupLabel = stringResource(Res.string.listen_together)
    val anchor = rememberGlasmioMenuAnchorState()
    DisposableEffect(router, expanded, routingAllowed) {
        fun update() {
            val controller = activeController()
            val system = router.systemController
            val transferable =
                if (Build.VERSION.SDK_INT >= 35) controller.transferableRoutes else emptyList()
            routes =
                (controller.selectedRoutes +
                        system.selectedRoutes +
                        controller.selectableRoutes +
                        transferable +
                        router.routes)
                    .distinctBy { it.id }
                    .sortedBy { if (it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) 0 else 1 }
            selected = controller.selectedRoutes.map { it.id }.toSet()
            routeVolume =
                if (controller.id == system.id)
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                else controller.volume.toFloat() / controller.volumeMax.coerceAtLeast(1)
        }
        val callback =
            object : MediaRouter2.RouteCallback() {
                override fun onRoutesUpdated(value: List<MediaRoute2Info>) {
                    update()
                }
            }
        val transfer =
            object : MediaRouter2.TransferCallback() {
                override fun onTransfer(
                    oldController: MediaRouter2.RoutingController,
                    newController: MediaRouter2.RoutingController,
                ) {
                    failed = false
                    update()
                }

                override fun onTransferFailure(route: MediaRoute2Info) {
                    failed = true
                    update()
                }
            }
        val controller =
            object : MediaRouter2.ControllerCallback() {
                override fun onControllerUpdated(value: MediaRouter2.RoutingController) {
                    update()
                }
            }
        router.registerRouteCallback(
            context.mainExecutor,
            callback,
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), expanded)
                .build(),
        )
        router.registerTransferCallback(context.mainExecutor, transfer)
        router.registerControllerCallback(context.mainExecutor, controller)
        update()
        onDispose {
            router.unregisterRouteCallback(callback)
            router.unregisterTransferCallback(transfer)
            router.unregisterControllerCallback(controller)
        }
    }
    LaunchedEffect(expanded) {
        while (expanded) {
            val controller = activeController()
            routeVolume =
                if (controller.id == router.systemController.id)
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                else controller.volume.toFloat() / controller.volumeMax.coerceAtLeast(1)
            delay(300)
        }
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().glasmioMenuAnchor(anchor), contentAlignment = Alignment.Center) {
            IconTouch(
                "airplay",
                label,
                {
                    expanded = true
                    failed = false
                    if (!routingAllowed) permissionRequest.launch(permissions)
                },
                Color.White.copy(alpha = .5f),
                26.dp,
                40.dp,
            )
        }
        if (expanded) {
            RequireMusicMenuRecording()
            val colors = defaultGlassColors(true, MusicAccent)
            CompositionLocalProvider(LocalGlassColors provides colors) {
                GlasmioCascadingMenu(
                    anchor.bounds,
                    items =
                        listOf(
                            GlasmioCascadingMenuItem(
                                title = groupLabel,
                                separatorBefore = true,
                                icon = { MusicIcon("shareplay", Color.White, 23.dp) },
                                onClick = onGroups,
                            )
                        ),
                    title = label,
                    expandedDescription = stringResource(Res.string.expanded),
                    collapsedDescription = stringResource(Res.string.collapsed),
                    onDismiss = { expanded = false },
                    backdrop = LocalBlurBackdrop.current,
                    menuAnchorState = anchor,
                    centerHorizontally = centerHorizontally,
                    headerContentHeight =
                        maxOf(
                            300.dp,
                            110.dp +
                                60.dp * routes.size.coerceAtMost(5) +
                                (if (failed) 28.dp else 0.dp),
                        ),
                    headerContent = { _ ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MusicArtwork(
                                    model,
                                    state,
                                    state.current,
                                    Modifier.size(60.dp),
                                    8.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    MusicText(
                                        state.current?.name.orEmpty(),
                                        17,
                                        Color.White,
                                        FontWeight.SemiBold,
                                    )
                                    MusicText(
                                        state.current?.artist.orEmpty(),
                                        15,
                                        Color.White.copy(alpha = .65f),
                                    )
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                routes.forEach { route ->
                                    val active = route.id in selected
                                    var routeWidth by remember(route.id) { mutableIntStateOf(1) }
                                    fun setVolume(value: Float) {
                                        routeVolume = value.coerceIn(0f, 1f)
                                        if (route.isSystemRoute)
                                            audio.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                (routeVolume *
                                                        audio.getStreamMaxVolume(
                                                            AudioManager.STREAM_MUSIC
                                                        ))
                                                    .toInt(),
                                                0,
                                            )
                                        else
                                            router.controllers
                                                .firstOrNull { c ->
                                                    c.selectedRoutes.any { it.id == route.id }
                                                }
                                                ?.let {
                                                    it.setVolume(
                                                        (routeVolume * it.volumeMax).toInt()
                                                    )
                                                }
                                    }
                                    val drag = rememberDraggableState { delta ->
                                        setVolume(routeVolume + delta / routeWidth.coerceAtLeast(1))
                                    }
                                    val icon =
                                        when (route.type) {
                                            MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> "iphone"
                                            MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
                                            MediaRoute2Info.TYPE_BLE_HEADSET,
                                            MediaRoute2Info.TYPE_WIRED_HEADSET,
                                            MediaRoute2Info.TYPE_WIRED_HEADPHONES -> "headphones"
                                            else -> "hifispeaker"
                                        }
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(48.dp)
                                            .onSizeChanged { routeWidth = it.width }
                                            .clip(musicShape(24.dp))
                                            .background(
                                                Color.White.copy(alpha = if (active) .4f else .12f)
                                            )
                                            .then(
                                                if (active)
                                                    Modifier.draggable(drag, Orientation.Horizontal)
                                                        .semantics {
                                                            progressBarRangeInfo =
                                                                ProgressBarRangeInfo(
                                                                    routeVolume,
                                                                    0f..1f,
                                                                )
                                                            setProgress {
                                                                setVolume(it)
                                                                true
                                                            }
                                                        }
                                                else Modifier
                                            )
                                    ) {
                                        if (active)
                                            Box(
                                                Modifier.fillMaxHeight()
                                                    .fillMaxWidth(routeVolume.coerceIn(0f, 1f))
                                                    .background(Color.White)
                                            )
                                        Row(
                                            Modifier.fillMaxSize()
                                                .clickable {
                                                    try {
                                                        router.transferTo(route)
                                                    } catch (_: Exception) {
                                                        failed = true
                                                    }
                                                }
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val foreground =
                                                if (active) Color.Black else Color.White
                                            MusicIcon(icon, foreground, 23.dp)
                                            Spacer(Modifier.width(10.dp))
                                            MusicText(
                                                if (
                                                    route.type ==
                                                        MediaRoute2Info.TYPE_BUILTIN_SPEAKER
                                                )
                                                    deviceName
                                                else route.name.toString(),
                                                17,
                                                foreground,
                                                FontWeight.Medium,
                                                Modifier.weight(1f),
                                            )
                                            if (active) MusicIcon("checkmark", foreground, 20.dp)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (failed)
                                MusicText(
                                    stringResource(Res.string.output_transfer_failed),
                                    12,
                                    Color.White.copy(alpha = .6f),
                                )
                        }
                    },
                )
            }
        }
    }
}
