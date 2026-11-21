// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.nav

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.kyant.shapes.Capsule
import com.mocharealm.bell.domain.model.SearchScope
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.composables.LocalMusicDark
import com.mocharealm.bell.ui.composables.LocalMusicGridColumns
import com.mocharealm.bell.ui.composables.LocalMusicGroupedPage
import com.mocharealm.bell.ui.composables.LocalMusicNativeSearch
import com.mocharealm.bell.ui.composables.LocalMusicSearchFocusChange
import com.mocharealm.bell.ui.composables.LocalMusicSearchFocused
import com.mocharealm.bell.ui.composables.LocalMusicSearchQuery
import com.mocharealm.bell.ui.composables.LocalMusicTabletMode
import com.mocharealm.bell.ui.composables.MusicAccent
import com.mocharealm.bell.ui.composables.MusicHeader
import com.mocharealm.bell.ui.composables.MusicNativeMenu
import com.mocharealm.bell.ui.composables.MusicText
import com.mocharealm.bell.ui.composables.NativeMusicPlayerBackdrop
import com.mocharealm.bell.ui.screen.search.composables.MusicSearchResults
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCombinedBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.scaffold.GlasmioFloatingSearch
import io.github.neoruaa.glasmio.scaffold.navigation.*
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MusicNavigationHost(
    route: String,
    selectedTab: String,
    model: BellStore,
    header: (String) -> MusicHeader,
    onBack: (String) -> Unit,
    onTab: (String) -> Unit,
    onSearch: () -> Unit,
    background: @Composable (String) -> Unit,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val text_app_name = stringResource(Res.string.app_name)
    val text_cancel = stringResource(Res.string.cancel)
    val text_favorites = stringResource(Res.string.favorites)
    val text_home = stringResource(Res.string.home)
    val text_library = stringResource(Res.string.library)
    val text_back = stringResource(Res.string.back)
    val text_voice_input_unavailable = stringResource(Res.string.voice_input_unavailable)
    val text_music_library = stringResource(Res.string.music_library)
    val text_search = stringResource(Res.string.search)
    val text_search_music = stringResource(Res.string.search_music)
    val text_search_placeholder = stringResource(Res.string.search_placeholder)

    var backStack by remember { mutableStateOf(listOf(route)) }
    val cardSources = remember { mutableMapOf<String, String>() }
    val artworkNavigation = LocalArtworkNavigation.current
    val screenCorners = playerWindowCorners()
    fun cardRoute(key: Any?) =
        key is String &&
            key.startsWith("album:") &&
            artworkNavigation
                ?.origins
                ?.get(key.removePrefix("album:").removeSuffix(":offline"))
                ?.token
                ?.startsWith("container:") == true
    fun AnimatedContentTransitionScope<Scene<String>>.cardChange(): Boolean {
        val from = initialState.entries.lastOrNull()?.contentKey
        val to = targetState.entries.lastOrNull()?.contentKey
        return (cardRoute(to) && cardSources[to] == from) ||
            (cardRoute(from) && cardSources[from] == to)
    }
    val entries = remember { mutableMapOf<String, NativeMusicEntry>() }
    DisposableEffect(entries) {
        onDispose {
            entries.values.forEach { page -> page.menus.values.forEach { it.anchor.release() } }
        }
    }
    fun entry(key: String) = entries.getOrPut(key) { NativeMusicEntry() }
    val currentBack by rememberUpdatedState(onBack)
    val motion = remember {
        GlasmioNavHeaderMotion(backStack.last()) { from, to, pop ->
            if (
                (from in rootPages && to in rootPages) ||
                    to == Routes.Search ||
                    (pop && from == Routes.Search)
            )
                GlasmioNavigationOperation.Tab
            else if (pop) GlasmioNavigationOperation.Pop else GlasmioNavigationOperation.Push
        }
    }
    val navigationFrame = motion.frame.value
    var backTransitionId by remember { mutableLongStateOf(-1L) }
    var pendingBackTarget by remember { mutableStateOf<String?>(null) }
    fun goBack() {
        if (pendingBackTarget == null && backTransitionId < 0L && backStack.size > 1) {
            val target = backStack[backStack.lastIndex - 1]
            pendingBackTarget = target
            backTransitionId = navigationFrame.transitionId + 1
            backStack = backStack.dropLast(1)
            currentBack(target)
        }
    }
    fun requestHeaderBack() {
        if (pendingBackTarget == null && backTransitionId < 0L && backStack.size > 1) {
            val target = backStack[backStack.lastIndex - 1]
            pendingBackTarget = target
            backTransitionId = navigationFrame.transitionId + 1
            currentBack(target)
        }
    }
    LaunchedEffect(navigationFrame.transitionId, navigationFrame.phase) {
        if (
            backTransitionId >= 0L &&
                navigationFrame.transitionId >= backTransitionId &&
                navigationFrame.phase == GlasmioNavigationPhase.Idle
        )
            backTransitionId = -1L
    }
    LaunchedEffect(route, selectedTab) {
        val pendingTarget = pendingBackTarget
        if (pendingTarget != null && route != pendingTarget) return@LaunchedEffect
        if (backStack.last() != route) {
            val existing = backStack.indexOf(route)
            when {
                route in rootPages && route == selectedTab -> {
                    backStack = listOf(route)
                }
                existing >= 0 -> backStack = backStack.take(existing + 1)
                else -> {
                    if (cardRoute(route)) cardSources[route] = backStack.last()
                    backStack = backStack + route
                }
            }
        }
        if (route == pendingTarget) pendingBackTarget = null
    }
    val active = backStack.last()
    val tabletMode = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val rootTabIconsSelected = active != Routes.Search
    val navScroll =
        rememberGlasmioNavigationScrollState(
            entry(active).list,
            enabled = !tabletMode && active != Routes.Search,
        )
    var pageSharedScope by remember { mutableStateOf<SharedTransitionScope?>(null) }
    val navigationBackdrop = rememberLayerBackdrop()
    val playerBackdrop = LocalMusicPlayerBackdropHost.current
    val livePlayerBackdrop =
        remember(navigationBackdrop) { NativeMusicPlayerBackdrop(navigationBackdrop) }
    SideEffect {
        livePlayerBackdrop.dark = header(active).dark
        playerBackdrop?.source = livePlayerBackdrop
    }
    val bottomBackdrop = rememberSharedGlassBackdrop(navigationBackdrop, navigationBackdrop)
    val adaptive = rememberGlasmioAdaptiveNavigationState()
    val layout = if (tabletMode) GlasmioNavigationLayout.TopTabs else glasmioNavigationLayout()
    val topTabs = layout == GlasmioNavigationLayout.TopTabs
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current.density
    val selected = if (active == Routes.Search) text_search else selectedTab
    val currentState by model.state.collectAsState()
    var searchQuery by remember { mutableStateOf(TextFieldValue()) }
    var searchBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var searchEditing by remember { mutableStateOf(false) }
    var searchEntryCompact by remember { mutableFloatStateOf(0f) }
    val searchFocused = active == Routes.Search && searchEditing
    LaunchedEffect(active) {
        if (active in rootPages && active != Routes.Search) {
            searchEditing = false
            searchQuery = TextFieldValue()
            model.accept(BellIntent.Search(""))
        }
    }
    val searching = searchFocused && !tabletMode
    val searchTransition = rememberGlasmioSearchTransition(searching)
    val landingTransition = rememberGlasmioSearchTransition(active == Routes.Search)
    fun openSearchPage() {
        searchEntryCompact = navScroll.compactProgress.value
        onSearch()
    }
    fun selectTabPage(target: String) {
        onTab(target)
    }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK)
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let {
                        searchQuery = TextFieldValue(it)
                        searchEditing = true
                        model.accept(BellIntent.Search(it))
                    }
        }
    val context = LocalContext.current
    val tabs =
        listOf(
            GlasmioTabItem(
                Routes.Home,
                text_home,
                if (rootTabIconsSelected && selectedTab == Routes.Home) SfSymbol.MusicHouseFilled
                else SfSymbol.MusicHouse,
            ),
            GlasmioTabItem(
                Routes.Library,
                text_library,
                if (rootTabIconsSelected && selectedTab == Routes.Library)
                    SfSymbol.SquareStackFilled
                else SfSymbol.SquareStack,
            ),
        )
    GlasmioAdaptiveNavigationScaffold(
        state = adaptive,
        layout = layout,
        sidebarTransition = GlasmioSidebarTransition.Automatic,
        contentBackdrop = navigationBackdrop,
        sidebar = {
            Column(
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp)
            ) {
                GlasmioSidebarToolbar(
                    leading = { MusicText(text_app_name, 18, weight = FontWeight.SemiBold) },
                    trailing = {
                        GlasmioIconButton({ adaptive.sidebarExpanded = false }) {
                            GlasmioSidebarToggleIcon()
                        }
                    },
                )
                GlasmioSidebarRow(
                    text_search,
                    SfSymbol.Search,
                    active == Routes.Search,
                    onClick = { openSearchPage() },
                )
                LazyColumn {
                    items(tabs, key = { it.key }) { tab ->
                        GlasmioSidebarRow(
                            tab.label,
                            tab.symbol,
                            active == tab.key,
                            onClick = { selectTabPage(tab.key) },
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GlasmioFloatingSearch(
                active = searching,
                autoFocus = true,
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    model.accept(BellIntent.Search(it.text))
                },
                onSearch = { model.accept(BellIntent.Search(it)) },
                onDismiss = {
                    searchEditing = false
                    searchQuery = TextFieldValue()
                    model.accept(BellIntent.Search(""))
                },
                placeholder = text_search_placeholder,
                cancelLabel = text_cancel,
                handleBack = true,
                backdrop = navigationBackdrop,
                transition = searchTransition,
                sourceBoundsInWindow = searchBounds,
                results = { padding ->
                    CompositionLocalProvider(
                        LocalMusicSharedScope provides pageSharedScope,
                        LocalMusicAnimatedScope provides entry(text_search).animation,
                    ) {
                        MusicSearchResults(
                            model,
                            currentState,
                            PaddingValues(
                                top = padding.calculateTopPadding() + 56.dp,
                                bottom = padding.calculateBottomPadding(),
                            ),
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize().nestedScroll(navScroll.nestedScrollConnection)) {
                    GlasmioNavigationScaffold(
                        motion = motion,
                        contentBackdrop = navigationBackdrop,
                        topBarPreferredWidth =
                            if (tabletMode)
                                GlasmioTabBarDefaults.preferredWidth(tabs, showsSidebar = true)
                                    .coerceAtMost(560.dp)
                            else null,
                        header = { key ->
                            val name = key as String
                            val spec = header(name)
                            val page = entry(name)
                            val actions =
                                spec.actions.map { action ->
                                    val menu =
                                        if (action.menu.isNotEmpty()) {
                                            val holder =
                                                page.menus.getOrPut(action.id) {
                                                    NativeMusicMenu(GlasmioMenuAnchorState())
                                                }
                                            holder.items = action.menu
                                            holder.dark = spec.dark
                                            holder
                                        } else null
                                    action to
                                        GlasmioHeaderAction(
                                            action.id,
                                            action.icon,
                                            action.label,
                                            onClick = {
                                                if (menu != null) menu.expanded = true
                                                else action.action()
                                            },
                                            menuAnchor = menu?.anchor,
                                        ) {
                                            // The scaffold reserves a 24dp icon slot plus 10dp on
                                            // each side.
                                            // Keep that 44dp button allocation, but let the image
                                            // cover it.
                                            if (action.avatarUrl != null)
                                                Box(
                                                    Modifier.size(24.dp)
                                                        .wrapContentSize(unbounded = true)
                                                        .requiredSize(36.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            Brush.linearGradient(
                                                                listOf(
                                                                    Color(0xFFAAC8E4),
                                                                    Color(0xFF7884BE),
                                                                )
                                                            )
                                                        ),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    MusicText(
                                                        action.avatarInitial,
                                                        19,
                                                        Color.White,
                                                        FontWeight.SemiBold,
                                                    )
                                                    AsyncImage(
                                                        action.avatarUrl,
                                                        action.label,
                                                        Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                }
                                            else
                                                when (action.icon) {
                                                    "ellipsis" -> SfIcon(SfSymbol.Ellipsis, null)
                                                    "artist" -> SfIcon(SfSymbol.PersonFilled, null)
                                                    "playlist" ->
                                                        SfIcon(SfSymbol.MusicNoteList, null)
                                                    "share" -> SfIcon("square.and.arrow.up", null)
                                                    "sort" ->
                                                        SfIcon("line.3.horizontal.decrease", null)
                                                    else -> SfIcon(action.icon, null)
                                                }
                                        }
                                }
                            GlasmioHeaderSpec(
                                title =
                                    if (name == Routes.Search && (searchFocused || tabletMode)) ""
                                    else spec.title,
                                leading =
                                    if (spec.root || name == Routes.Search) emptyList()
                                    else
                                        listOf(
                                            GlasmioHeaderGroup(
                                                "navigation",
                                                listOf(
                                                    GlasmioHeaderAction(
                                                        "back",
                                                        "chevron.backward",
                                                        text_back,
                                                        { requestHeaderBack() },
                                                    ) {
                                                        SfIcon(
                                                            SfSymbol.ChevronBack,
                                                            null,
                                                            mirrored = true,
                                                        )
                                                    }
                                                ),
                                            )
                                        ),
                                trailing =
                                    if (name == Routes.Search && (searchFocused || tabletMode))
                                        emptyList()
                                    else
                                        actions
                                            .groupBy { it.first.group }
                                            .map { (group, values) ->
                                                GlasmioHeaderGroup(
                                                    group,
                                                    values.map { it.second },
                                                    if (group == "account")
                                                        GlasmioToolbarActionStyle.Separated
                                                    else GlasmioToolbarActionStyle.Merged,
                                                )
                                            },
                                scrollState = page.scroll,
                                colors = defaultGlassColors(spec.dark, MusicAccent),
                            )
                        },
                        topBar =
                            if (topTabs)
                                ({ availableWidth ->
                                    GlasmioTabBar(
                                        tabs,
                                        selected,
                                        { selectTabPage(it) },
                                        { openSearchPage() },
                                        text_search,
                                        searchLabel = text_search,
                                        onSidebarToggle = adaptive::toggleSidebar,
                                        onSearchBoundsChanged = { searchBounds = it },
                                        animatePressScale = !tabletMode,
                                        modifier =
                                            Modifier.widthIn(max = minOf(560.dp, availableWidth))
                                                .onGloballyPositioned {
                                                    adaptive.tabBarBounds.value =
                                                        it.boundsInWindow()
                                                },
                                    )
                                })
                            else null,
                    ) { padding ->
                        // Keep the lifted page surfaces inside the content plane. Header,
                        // dock and menus remain above them and sample the moving page.
                        SharedTransitionLayout(Modifier.fillMaxSize()) {
                            val pageScope = this
                            SideEffect { pageSharedScope = pageScope }
                            CompositionLocalProvider(
                                LocalMusicSharedScope provides pageScope,
                                LocalNativeMusicPadding provides padding,
                            ) {
                                GlasmioNavDisplay(
                                    backStack = backStack,
                                    motion = motion,
                                    onBack = { goBack() },
                                    transitionSpec = {
                                        if (cardChange())
                                            fadeIn(tween(420), initialAlpha = 1f) togetherWith
                                                fadeOut(tween(420), targetAlpha = 1f)
                                        else if (rootTabChange()) fadeIn() togetherWith fadeOut()
                                        else
                                            iosTransitionSpec<String>(direction, density)
                                                .invoke(this)
                                    },
                                    popTransitionSpec = {
                                        if (cardChange())
                                            fadeIn(tween(420), initialAlpha = 1f) togetherWith
                                                fadeOut(tween(420), targetAlpha = 1f)
                                        else if (rootTabChange(searchReturn = true))
                                            fadeIn() togetherWith fadeOut()
                                        else
                                            iosPopTransitionSpec<String>(direction, density)
                                                .invoke(this)
                                    },
                                    predictivePopTransitionSpec = { edge ->
                                        if (cardChange())
                                            fadeIn(tween(420), initialAlpha = 1f) togetherWith
                                                fadeOut(tween(420), targetAlpha = 1f)
                                        else if (rootTabChange(searchReturn = true))
                                            fadeIn() togetherWith fadeOut()
                                        else
                                            iosPredictivePopTransitionSpec<String>(
                                                    direction,
                                                    density,
                                                )
                                                .invoke(this, edge)
                                    },
                                    entryProvider = { key ->
                                        NavEntry(key = key, contentKey = key) {
                                            val spec = header(key)
                                            val animation = LocalNavAnimatedContentScope.current
                                            entry(key).animation = animation
                                            CompositionLocalProvider(
                                                LocalMusicAnimatedScope provides animation,
                                                LocalNativeMusicEntry provides entry(key),
                                                LocalMusicDark provides spec.dark,
                                                LocalMusicNativeSearch provides
                                                    !(tabletMode && key == Routes.Search),
                                                LocalMusicTabletMode provides tabletMode,
                                                LocalNativeMusicPadding provides
                                                    if (
                                                        tabletMode &&
                                                            topTabs &&
                                                            key == Routes.Search
                                                    )
                                                        PaddingValues(
                                                            start =
                                                                padding.calculateStartPadding(
                                                                    direction
                                                                ),
                                                            top =
                                                                padding.calculateTopPadding() +
                                                                    56.dp,
                                                            end =
                                                                padding.calculateEndPadding(
                                                                    direction
                                                                ),
                                                            bottom =
                                                                padding.calculateBottomPadding(),
                                                        )
                                                    else padding,
                                                LocalMusicSearchFocused provides
                                                    (key == Routes.Search && searchFocused),
                                                LocalMusicSearchFocusChange provides
                                                    { focused ->
                                                        if (active == Routes.Search)
                                                            searchEditing = focused
                                                    },
                                                LocalMusicSearchQuery provides searchQuery.text,
                                                LocalNativeMusicBackground provides
                                                    {
                                                        background(key)
                                                    },
                                                LocalNativeMusicBottom provides
                                                    (WindowInsets.navigationBars
                                                        .asPaddingValues()
                                                        .calculateBottomPadding() +
                                                        if (topTabs) 80.dp else 150.dp),
                                            ) {
                                                val containerKey =
                                                    artworkNavigation
                                                        ?.origins
                                                        ?.get(
                                                            key.removePrefix("album:")
                                                                .removeSuffix(":offline")
                                                        )
                                                        ?.token
                                                        ?.takeIf {
                                                            key.startsWith("album:") &&
                                                                it.startsWith("container:")
                                                        }
                                                GlasmioBackdropHost(
                                                    Modifier.fillMaxSize()
                                                        .then(
                                                            musicSharedContainer(
                                                                containerKey,
                                                                screenCorners,
                                                                isDestination = true,
                                                            )
                                                        ),
                                                    sampledContent = { background(key) },
                                                    overlayContent = {
                                                        BoxWithConstraints(
                                                            Modifier.fillMaxSize()
                                                                .glasmioAdaptiveContent()
                                                        ) {
                                                            val gridColumns =
                                                                ((maxWidth - 40.dp + 12.dp) /
                                                                        176.dp)
                                                                    .toInt()
                                                                    .coerceAtLeast(2)
                                                            CompositionLocalProvider(
                                                                LocalMusicGridColumns provides
                                                                    gridColumns
                                                            ) {
                                                                content(key)
                                                                GlasmioPageTitle(key)
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    val dark = header(active).dark
                    CompositionLocalProvider(
                        LocalGlassColors provides defaultGlassColors(dark, MusicAccent),
                        LocalMusicDark provides dark,
                    ) {
                        GlasmioBackdropProvider(bottomBackdrop) {
                            val dockModifier =
                                Modifier.align(Alignment.BottomCenter)
                                    .then(
                                        if (tabletMode) Modifier.glasmioAdaptiveContent()
                                        else Modifier
                                    )
                                    .then(
                                        if (tabletMode)
                                            Modifier.fillMaxWidth(.8f).widthIn(max = 560.dp)
                                        else Modifier.fillMaxWidth()
                                    )
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 8.dp)
                                    .glasmioNavigationScrollHint(navScroll.scrollHintProgress)
                                    .navigationBarsPadding()
                            if (topTabs) Box(dockModifier) { miniPlayer() }
                            else
                                MusicBottomDock(
                                    tabs,
                                    selectedTab,
                                    navScroll.compactProgress,
                                    landingTransition.progressState,
                                    searchEntryCompact,
                                    searchPage = active == Routes.Search,
                                    searchEditing = searchFocused,
                                    focusTransition = searchTransition.progressState,
                                    query = searchQuery,
                                    onQuery = {
                                        searchQuery = it
                                        model.accept(BellIntent.Search(it.text))
                                    },
                                    onFocusSearch = { searchEditing = true },
                                    onSearchPage = ::openSearchPage,
                                    onReturnFromSearch = { goBack() },
                                    onTab = { selectTabPage(it) },
                                    onExpand = navScroll::expand,
                                    onSearchBounds = { searchBounds = it },
                                    backdrop = bottomBackdrop,
                                    modifier = dockModifier,
                                    onVoice = {
                                        try {
                                            voice.launch(
                                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                                    .putExtra(
                                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                                    )
                                                    .putExtra(
                                                        RecognizerIntent.EXTRA_PROMPT,
                                                        text_search_music,
                                                    )
                                            )
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                    context,
                                                    text_voice_input_unavailable,
                                                    android.widget.Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    },
                                    miniPlayer = miniPlayer,
                                )
                        }
                    }
                    entries.values
                        .flatMap { it.menus.values }
                        .forEach { menu ->
                            key(menu) {
                                if (menu.expanded) {
                                    CompositionLocalProvider(
                                        LocalGlassColors provides
                                            defaultGlassColors(menu.dark, MusicAccent),
                                        LocalMusicDark provides menu.dark,
                                    ) {
                                        MusicNativeMenu(
                                            menu.items,
                                            menu.anchor,
                                            navigationBackdrop,
                                        ) {
                                            menu.expanded = false
                                        }
                                    }
                                }
                            }
                        }
                }
            }
            if (searchFocused && !tabletMode)
                CompositionLocalProvider(
                    LocalGlassColors provides
                        defaultGlassColors(LocalMusicDark.current, MusicAccent)
                ) {
                    GlasmioSegmentedControl(
                        listOf(
                            SearchScope.Library to text_music_library,
                            SearchScope.Favorites to text_favorites,
                        ),
                        currentState.searchScope,
                        { model.accept(BellIntent.SearchScope(it)) },
                        modifier =
                            Modifier.align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                        height = 44.dp,
                        backdrop = navigationBackdrop,
                    )
                }
            AnimatedVisibility(
                visible = tabletMode && active == Routes.Search,
                modifier = Modifier.align(Alignment.TopCenter),
                enter =
                    fadeIn(tween(220, delayMillis = 35)) +
                        slideInVertically(tween(260)) { -it / 3 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 4 },
            ) {
                val dark = header(active).dark
                val chooserGlassBackdrop = rememberLayerBackdrop()
                val chooserBackdrop =
                    rememberCombinedBackdrop(navigationBackdrop, chooserGlassBackdrop)
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(dark, MusicAccent),
                    LocalMusicDark provides dark,
                ) {
                    GlasmioBackdropProvider(navigationBackdrop) {
                        Row(
                            Modifier.glasmioAdaptiveContent()
                                .statusBarsPadding()
                                .padding(top = if (topTabs) 64.dp else 4.dp)
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth()
                                .height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlasmioSearchToolbar(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    model.accept(BellIntent.Search(it.text))
                                },
                                onSearch = { model.accept(BellIntent.Search(it)) },
                                onCancel = {
                                    searchQuery = TextFieldValue()
                                    model.accept(BellIntent.Search(""))
                                },
                                cancelLabel = text_cancel,
                                placeholder = text_search_placeholder,
                                modifier = Modifier.weight(1f),
                                autoFocus = false,
                                morphProgress = 1f,
                                enabled = true,
                                showCancel = false,
                                onFieldFocusChanged = { searchEditing = it },
                            )
                            Box(
                                Modifier.widthIn(min = 154.dp, max = 210.dp).height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                GlassSurface(
                                    modifier = Modifier.matchParentSize(),
                                    backdrop = navigationBackdrop,
                                    shape = Capsule(),
                                    exportedBackdrop = chooserGlassBackdrop,
                                ) {}
                                GlasmioSegmentedControl(
                                    listOf(
                                        SearchScope.Library to text_music_library,
                                        SearchScope.Favorites to text_favorites,
                                    ),
                                    currentState.searchScope,
                                    { model.accept(BellIntent.SearchScope(it)) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    height = 40.dp,
                                    backdrop = chooserBackdrop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
actual fun MusicPage(
    title: String,
    largeTitle: Boolean,
    horizontalInset: Dp,
    underHeader: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val page = LocalNativeMusicEntry.current
    val list = page?.list ?: rememberLazyListState()
    val collapse = rememberGlasmioHeaderCollapse(list)
    val edge = rememberGlasmioHeaderScrollEdge(list)
    val currentCollapse = rememberUpdatedState(collapse)
    val currentEdge = rememberUpdatedState(edge)
    val scroll = remember {
        GlasmioHeaderScrollState({ currentCollapse.value }, { currentEdge.value })
    }
    SideEffect { page?.scroll = scroll }
    val host = LocalNativeMusicPadding.current
    val background = LocalNativeMusicBackground.current
    val pageBackdrop = rememberLayerBackdrop()
    val searchLanding = title == Routes.Search && !LocalMusicSearchFocused.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val padding =
        PaddingValues(
            start = horizontalInset,
            end = horizontalInset,
            top =
                if (underHeader) 0.dp
                else if (searchLanding && !LocalMusicTabletMode.current) status + 9.dp
                else host.calculateTopPadding(),
            bottom = LocalNativeMusicBottom.current,
        )
    CompositionLocalProvider(LocalBlurBackdrop provides pageBackdrop) {
        GlasmioPageBody(
            contentPadding = padding,
            backdrop = pageBackdrop,
            backgroundColor =
                if (LocalMusicGroupedPage.current)
                    defaultGlassColors(LocalMusicDark.current, MusicAccent).groupedBackground
                else if (LocalMusicDark.current) Color.Transparent else Color.White,
            topBarBlurProgress = edge,
            background = { background() },
        ) { bodyPadding ->
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().consumeWindowInsets(bodyPadding),
                contentPadding = bodyPadding,
            ) {
                if (largeTitle)
                    item(key = "native-large-title") {
                        androidx.compose.material3.Text(
                            title,
                            style = IosTypography.largeTitle,
                            fontWeight = FontWeight.Bold,
                            color = if (LocalMusicDark.current) Color.White else Color.Black,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = 1f - collapse
                                        scaleX = 1f - .04f * collapse
                                        scaleY = scaleX
                                        transformOrigin = TransformOrigin(0f, .5f)
                                    }
                                    .blur(6.dp * collapse)
                                    .offset(y = if (searchLanding) 0.dp else (-10).dp)
                                    .padding(
                                        horizontal = (20.dp - horizontalInset).coerceAtLeast(0.dp),
                                        vertical = 6.dp,
                                    ),
                        )
                    }
                content()
            }
        }
    }
}

@Composable
actual fun MusicAlphabetIndex(sections: List<Pair<String, Int>>) {
    val list = LocalNativeMusicEntry.current?.list ?: return
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(20.dp)
                .padding(end = 2.dp)
                .pointerInput(sections) {
                    fun jump(y: Float) {
                        if (sections.isNotEmpty()) {
                            val index =
                                (y / size.height * sections.size).toInt().coerceIn(sections.indices)
                            scope.launch { list.scrollToItem(sections[index].second) }
                        }
                    }
                    detectTapGestures { jump(it.y) }
                }
                .pointerInput(sections) {
                    detectDragGestures(
                        onDragStart = { position ->
                            if (sections.isNotEmpty()) {
                                val index =
                                    (position.y / size.height * sections.size)
                                        .toInt()
                                        .coerceIn(sections.indices)
                                scope.launch { list.scrollToItem(sections[index].second) }
                            }
                        }
                    ) { change, _ ->
                        change.consume()
                        if (sections.isNotEmpty()) {
                            val index =
                                (change.position.y / size.height * sections.size)
                                    .toInt()
                                    .coerceIn(sections.indices)
                            scope.launch { list.scrollToItem(sections[index].second) }
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEach { (letter, _) ->
                MusicText(letter, 10, MusicAccent, FontWeight.SemiBold, Modifier.height(14.dp))
            }
        }
    }
}
