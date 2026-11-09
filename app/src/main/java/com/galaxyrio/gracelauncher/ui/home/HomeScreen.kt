package com.galaxyrio.gracelauncher.ui.home

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.GraceWidget
import com.galaxyrio.gracelauncher.data.AppStackInset
import com.galaxyrio.gracelauncher.data.homeContentOwner
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesReorderState
import com.galaxyrio.gracelauncher.ui.overlays.rememberReorderTranslation
import com.galaxyrio.gracelauncher.ui.overlays.PopupWidgetItem
import com.galaxyrio.gracelauncher.ui.overlays.PopupWidgetControls
import kotlinx.coroutines.isActive
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.CategoryIcon
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.ClockColorPart
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.nextVisibleEvent
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.media.IdleMediaSessionId
import com.galaxyrio.gracelauncher.data.weather.WeatherCurrent
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.observeBlankDoubleTaps
import com.galaxyrio.gracelauncher.ui.components.observeListGestures
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherListGestureConnection
import com.galaxyrio.gracelauncher.ui.components.AppRowGestures
import com.galaxyrio.gracelauncher.ui.components.LauncherAppRow
import com.galaxyrio.gracelauncher.ui.components.favoriteUsageLabel
import com.galaxyrio.gracelauncher.ui.components.FolderRow
import com.galaxyrio.gracelauncher.ui.components.AppStack
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.startPadding
import com.galaxyrio.gracelauncher.ui.components.endPadding
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import com.galaxyrio.gracelauncher.ui.components.eventRemainingText
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberBatteryPercent
import com.galaxyrio.gracelauncher.ui.theme.resolvedFontColor
import com.galaxyrio.gracelauncher.ui.weather.HomeWeather
import com.galaxyrio.gracelauncher.ui.widgets.HomeWidget
import com.galaxyrio.gracelauncher.ui.widgets.HomeEditHandle
import com.galaxyrio.gracelauncher.ui.widgets.rememberHostedWidget
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    uiState: LauncherUiState,
    topSpace: Dp,
    onLaunchApp: (LauncherApp) -> Unit,
    onAppDetails: (LauncherApp) -> Unit,
    onAppShortcuts: (LauncherApp, Rect) -> Unit,
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    modifier: Modifier = Modifier,
    rowGestures: AppRowGestures = AppRowGestures(),
    highlightedAppKey: String? = null,
    onOpenFolder: (LauncherFolder, Rect) -> Unit = { _, _ -> },
    onEditFolder: (LauncherFolder) -> Unit = {},
    onFolderDrag: (LauncherFolder, Rect, Boolean) -> Unit = { _, _, _ -> },
    onFolderDragEnd: (Boolean) -> Unit = {},
    onMediaCommand: (String, MediaCommand) -> Unit = { _, _ -> },
    onDismissMedia: (String, Long) -> Boolean = { _, _ -> false },
    onWidgetMenu: () -> Unit = {},
    onCustomWidgetMenu: () -> Unit = {},
    editingLayout: Boolean = false,
    widgetInputEnabled: Boolean = true,
    onTopOffsetChange: (Float) -> Unit = {},
    onWidgetHeightChange: (Int) -> Unit = {},
    onHomeGesture: (GraceButtonGesture, Offset) -> Unit = { _, _ -> },
    actions: LauncherActions = LauncherActions(),
    onWidgetEditingChange: (Boolean) -> Unit = {},
    onWeatherClick: () -> Unit = {},
) {
    val now by rememberHomeTime()
    val headphones by rememberHeadphonesConnected()
    val localTime = now.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val homeContent = uiState.visibleHomeContent(headphones, localTime.hour * 60 + localTime.minute)
    val event = if (uiState.settings.calendarAgenda) nextVisibleEvent(uiState.events, now,
        deprioritizeAllDay = uiState.settings.calendar.deprioritizeAllDay) else null
    val entries = homeContent.items
    val categoryIcons = uiState.appCategories.associate { it.key to it.icon }
    val entriesByKey = entries.associateBy { it.key }
    val favoriteFolders = (uiState.folders + uiState.favoriteFolders).associateBy { it.id }
    var idleMediaDismissed by rememberSaveable(uiState.settings.mediaAlwaysVisible, uiState.settings.mediaPlayer) { mutableStateOf(false) }
    LaunchedEffect(uiState.homeMedia?.sessionId, uiState.homeMedia?.revision) {
        if (uiState.homeMedia != null) idleMediaDismissed = false
    }
    val media = uiState.homeMedia ?: if (uiState.settings.mediaPlayer && uiState.settings.mediaAlwaysVisible &&
        !idleMediaDismissed && !uiState.isLoadingSettings && !uiState.settingsLoadFailed) NowPlaying(
        sessionId = IdleMediaSessionId, playerName = stringResource(R.string.settings_media_player),
        title = stringResource(R.string.media_idle_title), artist = stringResource(R.string.media_idle_summary),
        playing = false, canToggle = true, canPrevious = false, canNext = false,
    ) else null
    val listState = rememberLazyListState()
    val homeLayout = uiState.settings.homeLayout
    var topOffset by remember(homeLayout.topOffsetDp) { mutableFloatStateOf(homeLayout.topOffsetDp) }
    var capturedBaseline by rememberSaveable { mutableStateOf<Float?>(null) }
    var baselinePinned by rememberSaveable { mutableStateOf(true) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val saveOrder by rememberUpdatedState<(List<String>) -> Unit>({ keys ->
        // A category remains one sortable homepage item even though it renders several rows.
        actions.reorderFavorites(keys.map { homeContent.owners[it] ?: it }.distinct())
    })
    val reorder = remember(listState) {
        FavoritesReorderState(listState, entries.map { it.key }, { saveOrder(it) }) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    var selectedWidget by rememberSaveable { mutableStateOf<String?>(null) }
    var resizing by remember { mutableStateOf(false) }
    var dragged by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<String, HomeLayout>() }
    val updateWidget: (String, HomeLayout, Boolean) -> Unit = { key, layout, save ->
        drafts[key] = layout
        if (save) actions.updatePopup(homeContentOwner(), emptyList()) { current ->
            current.map { if (it.key == key && it.widget != null) it.copy(widget = layout) else it }
        }
    }
    val saveWidget by rememberUpdatedState(updateWidget)
    val finishEditing = {
        selectedWidget?.let { key -> drafts[key]?.let { saveWidget(key, it, true) } }
        selectedWidget = null
    }
    BackHandler(enabled = selectedWidget != null) { finishEditing() }
    LaunchedEffect(entries) {
        reorder.synchronize(entries.map { it.key })
        drafts.keys.toList().forEach { key ->
            if (entries.none { it.key == key } || entriesByKey[key]?.widget == drafts[key]) drafts.remove(key)
        }
        if (entriesByKey[selectedWidget]?.widget == null) selectedWidget = null
    }
    val widgetEditing = selectedWidget != null || reorder.draggingKey != null
    val editingCallback by rememberUpdatedState(onWidgetEditingChange)
    LaunchedEffect(widgetEditing) { editingCallback(widgetEditing) }
    DisposableEffect(Unit) { onDispose { editingCallback(false) } }
    LaunchedEffect(widgetInputEnabled, editingLayout, homeLayout.editingLocked) {
        if (!widgetInputEnabled || editingLayout || homeLayout.editingLocked) {
            reorder.finish(true)
            finishEditing()
        }
    }
    LaunchedEffect(reorder.draggingKey, density) {
        if (reorder.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 640.dp.toPx() }
        var previous = withFrameNanos { it }
        while (isActive && reorder.draggingKey != null) {
            val frame = withFrameNanos { it }
            if (dragged) reorder.autoScroll(edge, speed * ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f))
            previous = frame
        }
    }
    val startDrag: (String) -> Unit = { key ->
        if (!homeLayout.editingLocked) { dragged = false; reorder.start(key) }
    }
    val drag: (Float) -> Unit = { delta -> if (delta != 0f) dragged = true; reorder.drag(delta) }
    val doubleTapSlop = remember(context, density) { android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    val minimumTop = stableStatusBarInset() + 8.dp
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(editingLayout, homeLayout.homeOrder) {
        baselinePinned = true
        if (editingLayout && !homeLayout.baselineEnabled) listState.scrollToItem(0)
    }
    val userIsEditing by rememberUpdatedState(widgetEditing || resizing || editingLayout)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect {
            if (it && !userIsEditing) baselinePinned = false
        }
    }
    val overscroll = rememberOverscrollEffect()
    val gesturesEnabled = widgetInputEnabled && !editingLayout && !widgetEditing && uiState.settings.homeGestures.enabled &&
        !uiState.isLoadingSettings && !uiState.settingsLoadFailed
    val doubleTapEnabled = gesturesEnabled && uiState.settings.homeGestures.doubleTap.active
    val latestGestures by rememberUpdatedState(uiState.settings.homeGestures)
    val latestEnabled by rememberUpdatedState(gesturesEnabled)
    val latestOnGesture by rememberUpdatedState(onHomeGesture)
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }
    val scrollConnection = rememberLauncherListGestureConnection(
        overscroll, listState, uiState.settings.homeGestures.swipeSensitivity,
        canTrigger = { latestEnabled && latestGestures.target(it).active },
        onGesture = { latestOnGesture(it, windowOffset + pointerPosition) },
        protectSwipeUp = uiState.settings.homeGestures.protectSwipeUp,
        protectSwipeDown = uiState.settings.homeGestures.protectSwipeDown,
        onPullReady = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
    )
    LaunchedEffect(gesturesEnabled) { if (!gesturesEnabled) scrollConnection.cancelGesture() }
    BoxWithConstraints(modifier.fillMaxSize()
        .onGloballyPositioned { windowOffset = it.positionInWindow() }
        .observeListGestures(scrollConnection) { pointerPosition = it }
        .pointerInput(doubleTapEnabled, doubleTapSlop, latestGestures.doubleTapIntervalMs) {
            if (!doubleTapEnabled) return@pointerInput
            observeBlankDoubleTaps(doubleTapSlop, latestGestures.doubleTapIntervalMs) { position ->
                if (latestEnabled && latestGestures.doubleTap.active) latestOnGesture(GraceButtonGesture.DoubleTap, windowOffset + position)
            }
        }) {
        val availableHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
        val maximumTop = (availableHeight - 56.dp).coerceAtLeast(minimumTop)
        val resolvedTop = (topSpace + topOffset.dp).coerceIn(minimumTop, maximumTop)
        val baselineOffset = homeLayout.baselineOffsetDp ?: capturedBaseline
        val baselineTop = (topSpace + topOffset.dp + (baselineOffset ?: 0f).dp).coerceIn(minimumTop, maximumTop)
        val baselineIndex = reorder.keys.indexOf(GraceWidget.Baseline.key)
        val aboveHeights = reorder.keys.take(baselineIndex.coerceAtLeast(0)).map { itemHeights[it] }
        val prefixHeight = if (aboveHeights.all { it != null }) with(density) { aboveHeights.filterNotNull().sum().toDp() } else null
        // Once measured, use real leading space, not scrollable virtual padding.
        // A short home list must still stretch immediately at its top boundary.
        val anchoredOrigin = if (baselineIndex >= 0 && baselineOffset != null && prefixHeight != null) baselineTop - prefixHeight else null
        val selectedLayout = selectedWidget?.let { drafts[it] ?: entriesByKey[it]?.widget }
        val toolbarHeight = if (selectedLayout != null) minimumTop + 104.dp else 0.dp
        val inputEnabled = widgetInputEnabled && !editingLayout && !uiState.isLoadingSettings && !uiState.settingsLoadFailed
        // When an off-screen prefix has not been measured yet, align by the
        // marker's key. Measured prefixes use ordinary padding and scroll bounds.
        LaunchedEffect(reorder.keys, aboveHeights, baselineTop, baselineOffset, toolbarHeight, baselinePinned, reorder.draggingKey) {
            if (baselinePinned && baselineIndex >= 0 && baselineOffset != null && reorder.draggingKey == null) {
                if (anchoredOrigin != null) listState.requestScrollToItem(0,
                    with(density) { (toolbarHeight - anchoredOrigin).coerceAtLeast(0.dp).roundToPx() })
                else listState.requestScrollToItem(baselineIndex, 0)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = toolbarHeight)
                .overscroll(overscroll).nestedScroll(scrollConnection).testTag("home_content"),
            contentPadding = PaddingValues(start = uiState.settings.listAppearance.startPadding,
                end = uiState.settings.listAppearance.endPadding,
                top = ((anchoredOrigin ?: if (baselineIndex >= 0) baselineTop else resolvedTop) - toolbarHeight).coerceAtLeast(0.dp),
                bottom = if (baselineIndex >= 0 && (anchoredOrigin == null || anchoredOrigin < toolbarHeight))
                    (availableHeight - baselineTop).coerceAtLeast(72.dp + bottomInset) else 72.dp + bottomInset),
            userScrollEnabled = !editingLayout && reorder.draggingKey == null && !resizing,
            overscrollEffect = null,
        ) {
            items(reorder.keys.mapNotNull(entriesByKey::get), key = { FavoritesReorderState.itemKey(it.key) }) { entry ->
                val grace = GraceWidget.fromKey(entry.key)
                val widget = drafts[entry.key] ?: entry.widget
                val app = uiState.findItem(entry.key)
                val dragging = reorder.draggingKey == entry.key
                val offset by rememberReorderTranslation(reorder, entry.key)
                var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val currentStart by rememberUpdatedState(startDrag)
                val currentDrag by rememberUpdatedState(drag)
                Box(Modifier.fillMaxWidth()
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .zIndex(if (dragging || reorder.drop?.key == entry.key) 1f else 0f)
                    .graphicsLayer { translationY = offset }
                    .onGloballyPositioned { coordinates = it }
                    .onSizeChanged { itemHeights[entry.key] = it.height }
                    .testTag("home_item:${entry.key}")
                    .pointerInput(grace, inputEnabled, homeLayout.editingLocked) {
                        if (grace != null && grace != GraceWidget.Baseline && inputEnabled && !homeLayout.editingLocked) {
                            var previous = Offset.Zero
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    previous = coordinates?.localToRoot(it) ?: it
                                    currentStart(entry.key)
                                },
                                onDragEnd = { reorder.finish(false) },
                                onDragCancel = { reorder.finish(true) },
                            ) { change, _ ->
                                val root = coordinates?.localToRoot(change.position) ?: change.position
                                change.consume(); currentDrag(root.y - previous.y); previous = root
                            }
                        }
                    }) {
                    when {
                        grace == GraceWidget.Baseline -> Spacer(Modifier.fillMaxWidth().height(2.dp)
                            .testTag("home_baseline").onGloballyPositioned {
                                if (baselineOffset == null && baselinePinned && !listState.isScrollInProgress && inputEnabled) {
                                    val measured = ((it.positionInWindow().y - windowOffset.y) / density.density - resolvedTop.value).coerceIn(0f, 1600f)
                                    capturedBaseline = measured
                                    actions.updateSettings { current ->
                                        if (current.homeLayout.baselineOffsetDp != null) current else
                                            current.copy(homeLayout = current.homeLayout.copy(baselineOffsetDp = measured))
                                    }
                                }
                            })
                        grace == GraceWidget.ClockCalendar -> HomeClockHeader(now, event, onDateClick, onClockClick,
                            showBattery = true,
                            clockStyle = uiState.settings.clockStyle,
                            weather = uiState.weather.snapshot?.current.takeIf { uiState.settings.weatherEnabled &&
                                !uiState.isLoadingSettings && !uiState.settingsLoadFailed },
                            weatherSnapshot = uiState.weather.snapshot.takeIf { uiState.settings.weatherEnabled &&
                                !uiState.isLoadingSettings && !uiState.settingsLoadFailed },
                            showClock = uiState.settings.clockEnabled, showCalendar = uiState.settings.calendarAgenda,
                            agendaEvents = if (uiState.settings.calendarAgenda) uiState.events else emptyList(),
                            tasks = uiState.tasks.tasks, showTasks = uiState.settings.calendar.tasksOrg.enabled,
                            taskPreferences = uiState.settings.calendar.tasksOrg,
                            onTaskClick = { onDateClick() },
                            calendarAboveClock = uiState.settings.calendarAboveClock,
                            deprioritizeAllDay = uiState.settings.calendar.deprioritizeAllDay,
                            interactive = inputEnabled, onLongClick = {}, onWeatherClick = onWeatherClick,
                            clockActive = inputEnabled)
                        // Keep the slot while empty so its height springs to zero instead of
                        // disappearing in one measure pass. Following rows flow into the gap.
                        grace == GraceWidget.Media -> Box(Modifier.fillMaxWidth().animateContentSize(
                            animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
                        )) {
                            if (media != null) HomeMediaPlayer(media, onMediaCommand,
                                style = uiState.settings.mediaStyle,
                                iconDesign = uiState.settings.iconDesign?.design ?: IconDesign(),
                                albumContainerColor = rememberMediaAlbumContainerColor(media.artworkSeedColor, uiState.settings),
                                keepWhilePlaying = uiState.settings.mediaKeepWhilePlaying,
                                dismissResistance = uiState.settings.dismissResistance,
                                dismissThresholdDp = uiState.settings.dismissThresholdDp,
                                onDismiss = { id, revision ->
                                    if (id == IdleMediaSessionId) { idleMediaDismissed = true; true }
                                    else onDismissMedia(id, revision).also { if (it) idleMediaDismissed = true }
                                })
                        }
                        widget != null -> PopupWidgetItem(widget, selected = selectedWidget == entry.key,
                            enabled = inputEnabled, editable = !homeLayout.editingLocked, haptics = uiState.settings.allowHapticFeedback,
                            horizontalInset = 8.dp,
                            maxHeight = 1600,
                            onChange = { layout, save -> updateWidget(entry.key, layout, save) },
                            onResizing = { resizing = it }, onDragStart = { startDrag(entry.key) },
                            onDrag = drag, onDragEnd = { cancelled ->
                                reorder.finish(cancelled)
                                if (!cancelled && !homeLayout.editingLocked) selectedWidget = entry.key
                            })
                        app != null -> {
                            val categoryIcon = categoryIcons[homeContent.owners[entry.key]] ?: CategoryIcon.None
                            val folder = app.folderId?.let(favoriteFolders::get)
                            if (folder?.isAppStack == true) AppStack(folder, uiState,
                                onLaunch = { item, bounds -> rowGestures.onLaunchAt?.invoke(item, bounds) ?: onLaunchApp(item) },
                                onOpenPopup = { item, bounds -> onAppShortcuts(item, bounds) },
                                onDetails = onAppDetails, onEdit = { onEditFolder(folder) },
                                modifier = Modifier.padding(horizontal = LauncherLayout.ContentInset - AppStackInset.dp, vertical = 4.dp))
                            else if (folder != null) FolderRow(folder, app = app,
                                highlighted = highlightedAppKey == folder.key,
                                notifications = uiState.notificationPreviewsFor(app),
                                onOpen = { onOpenFolder(folder, it) }, onLongClick = { onEditFolder(folder) },
                                onDrag = { bounds, expanded -> onFolderDrag(folder, bounds, expanded) },
                                onDragEnd = onFolderDragEnd,
                                onSwipeLeft = rowGestures.onOpenFirst?.let { open -> { bounds -> open(app, bounds) } },
                                showLabel = uiState.settings.listAppearance.showNames(favorites = true),
                                categoryIcon = categoryIcon)
                            else LauncherAppRow(app, onClick = { onLaunchApp(app) }, onLongClick = { onAppDetails(app) },
                                usageText = favoriteUsageLabel(app, uiState),
                                onSwipeRight = { onAppShortcuts(app, it) }, gestures = rowGestures,
                                highlighted = highlightedAppKey == app.key,
                                notifications = uiState.notificationPreviewsFor(app),
                                showLabel = uiState.settings.listAppearance.showNames(favorites = true),
                                categoryIcon = categoryIcon)
                        }
                    }
                }
            }
        }
        if (selectedLayout != null) Surface(Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp).offset(y = minimumTop).zIndex(4f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            PopupWidgetControls(selectedLayout,
                onChange = { layout, save -> selectedWidget?.let { updateWidget(it, layout, save) } }, onDone = finishEditing)
        }
        if (editingLayout) HomeEditHandle(
            label = stringResource(R.string.homepage_adjust_height), changed = topOffset != 0f, tag = "home_position_handle",
            onReset = { topOffset = 0f; onTopOffsetChange(0f) },
            onDelta = { delta ->
                val anchorOffset = if (baselineIndex >= 0) baselineOffset ?: 0f else 0f
                val current = (topSpace.value + topOffset + anchorOffset).coerceIn(minimumTop.value, maximumTop.value)
                topOffset = (current + delta).coerceIn(minimumTop.value, maximumTop.value) - topSpace.value - anchorOffset
            }, onFinished = { onTopOffsetChange(topOffset) },
            modifier = Modifier.padding(start = uiState.settings.listAppearance.startPadding, end = uiState.settings.listAppearance.endPadding)
                .offset(y = (if (baselineIndex >= 0) baselineTop else resolvedTop) - 20.dp))
    }
}

@Composable
internal fun LegacyHomeClockHeader(
    now: Instant,
    event: ScheduleEvent?,
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    showBattery: Boolean,
    clockStyle: ClockStyle,
    weather: WeatherCurrent?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    clockTag: String = "home_clock",
    dateTag: String = "home_date",
    onLongClick: () -> Unit = {},
    onAnchorOffsetChange: (Int) -> Unit = {},
    showClock: Boolean = true,
    showCalendar: Boolean = true,
    calendarAboveClock: Boolean = false,
    onWeatherClick: () -> Unit = {},
) {
    val appearance = LocalLauncherAppearance.current
    val visibility = clockStyle.effectiveVisibility
    val week = clockStyle.layout.week
    val clockVisible = showClock && visibility.showClock
    val weekdayVisible = showCalendar && visibility.showWeekday
    val dateVisible = showCalendar && visibility.showDate
    val battery = if (showCalendar && showBattery && visibility.showBattery) rememberBatteryPercent() else null
    val visibleWeather = weather?.takeIf { visibility.showWeather && it.temperature != null }
    val colors = ClockColorPart.entries.associateWith { resolvedFontColor(clockStyle.colors[it], onWallpaper = true) }
    val digitColors = listOf(ClockColorPart.HourTens, ClockColorPart.HourOnes, ClockColorPart.MinuteTens, ClockColorPart.MinuteOnes,
        ClockColorPart.SecondTens, ClockColorPart.SecondOnes)
        .map(colors::getValue)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val clockText = formatHomeClock(now, DateFormat.is24HourFormat(context), locale, leadingZero = clockStyle.face.showLeadingZero)
    val weekdayLocale = remember(locale, week, clockStyle.face.useLatinLetters) {
        clockCalendarLocale(locale, week && clockStyle.face.useLatinLetters)
    }
    val weekday = clockCalendarDate(now, weekdayLocale, true, false,
        colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day)).text
    val dateText = clockCalendarDate(now, locale, weekdayVisible && !week, dateVisible,
        colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day))
    val calendarText = buildAnnotatedString {
        append(dateText)
        if (battery != null) {
            if (length > 0) append("  ")
            withStyle(SpanStyle(color = colors.getValue(ClockColorPart.Battery))) { append("$battery%") }
        }
    }
    val completeDate = clockCalendarDate(now, locale, true, true,
        colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day)).text
    val dateDescription = stringResource(R.string.date_calendar_action, completeDate)
    val clockDescription = stringResource(R.string.clock_action, clockText)
    val largeFaceVisible = if (week) weekdayVisible else clockVisible
    val smallClockVisible = week && clockVisible
    val agendaVisible = showCalendar && event != null
    val rowVisible = smallClockVisible || calendarText.isNotEmpty() || visibleWeather != null
    val calendarContentVisible = rowVisible || agendaVisible
    val calendarAction = showCalendar && (calendarText.isNotEmpty() || agendaVisible)

    val clockContent: @Composable () -> Unit = {
        if (largeFaceVisible) ClockFace(
            time = if (week) weekday else clockText,
            style = clockStyle,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(clockTag)
                .semantics { contentDescription = if (week) dateDescription else clockDescription }
                .then(if (interactive) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = if (week) onDateClick else onClockClick,
                    onLongClick = onLongClick,
                ) else Modifier)
                .padding(horizontal = LauncherLayout.ContentInset),
            color = if (week) colors.getValue(ClockColorPart.Weekday) else appearance.text,
            digitColors = digitColors.takeUnless { week },
        )
    }
    val calendarContent: @Composable () -> Unit = {
        val shape = LauncherLayout.RowShape
        val interactions = remember { MutableInteractionSource() }
        if (calendarContentVisible) Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(dateTag)
                .then(if (calendarAction) Modifier.semantics { contentDescription = dateDescription } else Modifier)
                .then(if (interactive && calendarAction) Modifier.combinedClickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onClick = onDateClick,
                    onLongClick = onLongClick,
                ) else Modifier),
            propagateMinConstraints = true,
        ) {
            val dateStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Normal,
                shadow = appearance.textShadow,
            )
            CompositionLocalProvider(LocalContentColor provides appearance.text) {
                Column(Modifier.padding(vertical = LauncherLayout.ContentInset)) {
                    if (rowVisible) Row(Modifier.padding(horizontal = LauncherLayout.ContentInset, vertical = 2.dp)) {
                        if (smallClockVisible) {
                            ClockTimeLabel(
                                time = clockText, style = clockStyle, textStyle = dateStyle,
                                modifier = Modifier.alignByBaseline().testTag("${clockTag}_time")
                                    .semantics { contentDescription = clockDescription }
                                    .then(if (interactive) Modifier.combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                        onClick = onClockClick,
                                        onLongClick = onLongClick,
                                    ) else Modifier),
                                color = appearance.text,
                                digitColors = digitColors,
                            )
                            if (calendarText.isNotEmpty()) Text(" · ", modifier = Modifier.alignByBaseline(), color = appearance.text, style = dateStyle)
                        }
                        if (calendarText.isNotEmpty()) Text(
                            text = calendarText,
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("${dateTag}_text"),
                            color = appearance.text,
                            style = dateStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (visibleWeather != null) {
                            if (smallClockVisible || calendarText.isNotEmpty()) Spacer(Modifier.width(10.dp))
                            HomeWeather(visibleWeather, colors.getValue(ClockColorPart.Weather), dateStyle, Modifier.alignByBaseline(),
                                onClick = onWeatherClick.takeIf { interactive }, onLongClick = onLongClick.takeIf { interactive },
                                iconColor = colors.getValue(ClockColorPart.WeatherIcon))
                        }
                    }
                    if (agendaVisible) {
                        ScheduleLine(event = event, now = now, color = colors.getValue(ClockColorPart.Agenda))
                    }
                }
            }
            if (interactive && calendarAction) {
                // Keep the legacy calendar's rounded ripple separate from its text and icons.
                Box(Modifier.matchParentSize().clip(shape)
                    .indication(interactions, ripple(color = appearance.text)))
            }
        }
    }
    Column(modifier) {
        if (calendarAboveClock) calendarContent() else clockContent()
        Spacer(Modifier.height(if (largeFaceVisible && calendarContentVisible) 2.dp else 0.dp)
            .onGloballyPositioned { onAnchorOffsetChange(it.positionInParent().y.roundToInt()) })
        if (calendarAboveClock) clockContent() else calendarContent()
    }
}

@Composable
internal fun ScheduleLine(event: ScheduleEvent, now: Instant, color: Color = LocalLauncherAppearance.current.text) {
    val appearance = LocalLauncherAppearance.current
    val remaining = eventRemainingText(event, now)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.Normal,
        shadow = appearance.textShadow,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_line")
            .padding(horizontal = LauncherLayout.ContentInset, vertical = 2.dp),
    ) {
        Text(
            text = event.title,
            // CJK fallback and Latin fonts can have different ascents at the same size.
            modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("schedule_title"),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "· $remaining",
            modifier = Modifier.alignByBaseline().testTag("schedule_remaining"),
            style = style,
            color = color,
            maxLines = 1,
        )
    }
}
