package com.galaxyrio.gracelauncher.ui.overlays

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.platform.widthBounds
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.widgets.HomeWidget
import com.galaxyrio.gracelauncher.ui.widgets.WidgetLongPressConfiguration
import com.galaxyrio.gracelauncher.ui.widgets.WidgetConfigurationButton
import com.galaxyrio.gracelauncher.ui.widgets.rememberHostedWidget
import kotlin.math.roundToInt

@Composable
internal fun PopupWidgetControls(
    layout: HomeLayout, onChange: (HomeLayout, Boolean) -> Unit, onDone: () -> Unit,
) {
    val done = stringResource(R.string.done)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("popup_widget_controls")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Keep the Done action reachable on narrow pop-ups or with large fonts.
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(layout.popupFullWidth, onClick = { onChange(layout.copy(popupFullWidth = !layout.popupFullWidth), true) },
                    label = { Text(stringResource(R.string.popup_widget_full_width), maxLines = 1) },
                    modifier = Modifier.testTag("popup_widget_full_width"))
                FilterChip(layout.popupBackground, onClick = { onChange(layout.copy(popupBackground = !layout.popupBackground), true) },
                    label = { Text(stringResource(R.string.popup_widget_background), maxLines = 1) },
                    modifier = Modifier.testTag("popup_widget_background"))
                WidgetConfigurationButton(layout, onBeforeOpen = onDone)
            }
            IconButton(onClick = onDone, modifier = Modifier.size(40.dp).testTag("popup_widget_done")
                .semantics { contentDescription = done }) { LauncherIcon(LauncherSymbol.Check) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.popup_widget_content_scale, layout.popupContentScale),
                modifier = Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelMedium)
            Slider(value = layout.popupContentScale.toFloat(),
                onValueChange = { onChange(layout.copy(popupContentScale = it.roundToInt()), false) },
                onValueChangeFinished = { onChange(layout, true) }, valueRange = 50f..150f,
                modifier = Modifier.weight(1f).testTag("popup_widget_scale"))
        }
        // 2026-11-10 新增：圆角滑块。
        // 只对小组件生效，不影响应用图标行——因为裁剪是加在小组件宿主的 Modifier 上的。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.popup_widget_corner, layout.popupWidgetCornerDp),
                modifier = Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelMedium)
            Slider(value = layout.popupWidgetCornerDp.toFloat(),
                onValueChange = { onChange(layout.copy(popupWidgetCornerDp = it.roundToInt()), false) },
                onValueChangeFinished = { onChange(layout, true) }, valueRange = 0f..48f,
                modifier = Modifier.weight(1f).testTag("popup_widget_corner"))
        }
    }
}

/** A pop-up is a vertical list, so width is centered and height changes the panel's measured size. */
@Composable
internal fun PopupWidgetItem(
    layout: HomeLayout, selected: Boolean, enabled: Boolean, haptics: Boolean,
    maxHeight: Int, onChange: (HomeLayout, Boolean) -> Unit,
    onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Boolean) -> Unit,
    onResizing: (Boolean) -> Unit,
    horizontalInset: Dp = 18.dp,
    editable: Boolean = true,
    /**
     * 2026-11-11 新增：向外扩出的量（dp），用来抵消外层容器的左右内边距。
     *
     * 为什么需要它：这个组件在桌面列表里被 LazyColumn 的 contentPadding 左右各切一刀，
     * 桌面 startPadding=36dp、endPadding=56dp，合计 92dp，
     * 屏宽 360dp 的机器上组件只剩 268dp（占屏 74.4%）。用户要更宽，就得从这里出血。
     * 用负 padding 不行（Compose 不接受），改用 layout 的 placeWithLayer 向外偏移。
     */
    horizontalBleed: Dp = 0.dp,
) = WidgetLongPressConfiguration {
    val hosted = rememberHostedWidget(layout)
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val currentLayout by rememberUpdatedState(layout)
    val currentChange by rememberUpdatedState(onChange)
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentResizing by rememberUpdatedState(onResizing)
    val label = stringResource(R.string.widget_resize)
    val color = MaterialTheme.colorScheme.primary
    val padding = remember(hosted.info, context) {
        hosted.info?.let { AppWidgetHostView.getDefaultPaddingForWidget(context, it.provider, null) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        // 2026-11-11：出血量换算成像素，并把它算进可用宽度。
        // 打满后组件宽度 = 列表可用宽度 + 出血×2，正好吃掉外层 contentPadding。
        val bleedDp = horizontalBleed.coerceIn(0.dp, 48.dp)
        val available = (maxWidth.value - horizontalInset.value * 2 + bleedDp.value * 2).coerceAtLeast(1f)
        val bounds = hosted.info?.widthBounds(((padding?.left ?: 0) + (padding?.right ?: 0)).toFloat(),
            density, available.toInt().coerceAtLeast(1), 64.coerceAtMost(available.toInt().coerceAtLeast(1)))
        val limitWidth = bounds?.max?.toFloat() ?: available
        val minWidth = (bounds?.min?.toFloat() ?: 64f.coerceAtMost(available))
            .coerceAtLeast(available * .2f).coerceAtMost(limitWidth)
        // 2026-11-11 修正：原来 popupFullWidth 直接取 maxWidth.value（已被外层 padding 限制），
// 所以「全宽」也全不到哪去。改成取算好的 width，它已经把出血量加进去了。
        val width = if (layout.popupFullWidth) available.coerceAtMost(limitWidth) else
            (available * layout.popupWidthPercent.coerceIn(20, 100) / 100f).coerceIn(minWidth, limitWidth)
        val limitHeight = maxHeight.coerceIn(48, 1600).coerceAtMost(hosted.maxHeight)
        val minHeight = hosted.minHeight.coerceAtMost(limitHeight)
        val height = (layout.widgetHeightDp.takeIf { it > 0 } ?: hosted.defaultHeight).coerceIn(minHeight, limitHeight)
        val currentWidth by rememberUpdatedState(width)
        val currentHeight by rememberUpdatedState(height)
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // 2026-11-11：真正的出血实现，分两步。
        //
        // 第一步，算出「视觉目标宽」：可用宽 + 出血×2。桌面场景 268+48 = 316dp。
        // 第二步用 layout 拦截：把子内容按 316dp 去测量（突破父级 268dp 的约束），
        // 但只向父级报告 268dp 的占位，再把内容整体左移 24dp。
        // 这样父级排版不受影响，内容却能画进 LazyColumn 的左右 padding 区域。
        // 前提是 placeWithLayer 必须 clip = false，否则超出部分会被裁掉。
        val bleedPx = with(LocalDensity.current) { bleedDp.roundToPx() }
        val targetWidthPx = with(LocalDensity.current) { width.dp.roundToPx() }
        val targetWidth = (width + bleedDp * 2).coerceAtMost(360f)
        val targetWidthPxFinal = with(LocalDensity.current) { targetWidth.dp.roundToPx() }
        val targetHeightPx = with(LocalDensity.current) { height.dp.roundToPx() }
        Box(Modifier.testTag("popup_widget:${layout.widgetId}")
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(
                    minWidth = targetWidthPxFinal, maxWidth = targetWidthPxFinal,
                    minHeight = targetHeightPx, maxHeight = targetHeightPx))
                layout(constraints.maxWidth.coerceAtMost(targetWidthPx), targetHeightPx) {
                    placeable.placeWithLayer(-bleedPx, 0) { clip = false }
                }
            }
            .onGloballyPositioned { coordinates = it }) {
            // width 用「目标宽」而不是 fillMaxSize：父级只给了 268dp，
            // 但 layout 里我们已按 316dp 测量，这里必须同步用 316dp，
            // 否则内容仍会被填成 268dp，出血白做。
            Box(Modifier.width(targetWidth.dp).height(height.dp).clip(RoundedCornerShape(16.dp))
                .background(if (layout.popupBackground) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                HomeWidget(layout, hosted, height, editing = selected, enabled = enabled, editGesturesEnabled = editable,
                    hapticsEnabled = haptics, onLongPress = {},
                    onDragStart = { _, _ -> currentStart() },
                    onDrag = { _, y -> currentDrag(y) }, onDragEnd = { currentEnd(it) },
                    contentScale = layout.popupContentScale / 100f, removeHorizontalPadding = layout.popupFullWidth,
                    cornerRadiusDp = layout.popupWidgetCornerDp.toFloat())
            }
            if (selected) {
                // The native child is disabled while selected. Only this widget's
                // body gets a held-drag recognizer; app rows retain their details action.
                Box(Modifier.matchParentSize().pointerInput(enabled, editable) {
                    if (enabled && editable) {
                        var previous = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                previous = coordinates?.localToRoot(it) ?: it
                                currentStart()
                            },
                            onDragEnd = { currentEnd(false) }, onDragCancel = { currentEnd(true) },
                        ) { change, _ ->
                            val root = coordinates?.localToRoot(change.position) ?: change.position
                            change.consume()
                            currentDrag(root.y - previous.y)
                            previous = root
                        }
                    }
                })
                Box(Modifier.matchParentSize().border(2.dp, color, RoundedCornerShape(16.dp)))
                val horizontal = !layout.popupFullWidth && minWidth < limitWidth && (hosted.info == null ||
                    hosted.info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0)
                val vertical = minHeight < limitHeight && (hosted.info == null ||
                    hosted.info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0)
                val edges = buildList {
                    if (horizontal) { add("left" to Alignment.CenterStart); add("right" to Alignment.CenterEnd) }
                    if (vertical) { add("top" to Alignment.TopCenter); add("bottom" to Alignment.BottomCenter) }
                }
                for ((edge, alignment) in edges) key(edge) {
                    var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Box(Modifier.align(alignment).size(40.dp)
                        .onGloballyPositioned { handle = it }
                        .testTag("popup_widget_resize:$edge").semantics { contentDescription = label }
                        .pointerInput(enabled, minWidth, limitWidth, available, minHeight, limitHeight) {
                            if (enabled) awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                down.consume()
                                val original = currentLayout
                                var draft = original
                                var w = currentWidth
                                var h = currentHeight.toFloat()
                                var previous = handle?.localToRoot(down.position) ?: down.position
                                var complete = false
                                currentResizing(true)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (event.changes.count { it.pressed } > 1) break
                                        val root = handle?.localToRoot(change.position) ?: change.position
                                        val delta = (root - previous) / density
                                        previous = root
                                        if (edge == "left" || edge == "right") {
                                            // Centered width: both edges travel half the total size change.
                                            w = (w + delta.x * if (edge == "left") -2f else 2f).coerceIn(minWidth, limitWidth)
                                            draft = draft.copy(popupWidthPercent = (w / available * 100).roundToInt().coerceIn(20, 100))
                                        } else {
                                            h = (h + delta.y * if (edge == "top") -1f else 1f).coerceIn(minHeight.toFloat(), limitHeight.toFloat())
                                            draft = draft.copy(widgetHeightDp = h.roundToInt())
                                        }
                                        change.consume()
                                        currentChange(draft, false)
                                        if (!change.pressed) { complete = true; break }
                                    }
                                } finally {
                                    currentChange(if (complete) draft else original, complete)
                                    currentResizing(false)
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).background(color, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
                    }
                }
            }
        }
    }
}
