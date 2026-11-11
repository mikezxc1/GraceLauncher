@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.home.clockBatteryIcon
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ClockControl(val key: String, val content: @Composable (Int, Int) -> Unit)
private class ClockElementDrop(val key: String, val center: Float)

/** Each heading owns a separate segmented list, including its element buttons. */
internal fun LazyListScope.clockRegionSettings(
    regions: ClockRegions,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    onChange: ((ClockRegions) -> ClockRegions) -> Unit,
    onFont: (ClockElement) -> Unit,
    onWeatherIcons: () -> Unit,
    onWeatherContent: () -> Unit,
    onAgendaContent: () -> Unit,
    onTaskContent: () -> Unit,
    onRegionFont: (ClockRegion) -> Unit,
) {
    fun group(region: ClockRegion, controls: List<ClockControl>) {
        item("clock_region_heading:$region") { SettingsHeading(region.label()) }
        controls.forEachIndexed { index, control ->
            clockSetting("clock_region:$region:${control.key}") { control.content(index, controls.size) }
        }
    }
    fun changeFace(element: ClockElement, transform: (ClockFaceStyle) -> ClockFaceStyle) =
        onChange { current ->
            current.copy(primaryFaces = current.primaryFaces + (element to transform(current.face(element))))
        }
    fun weatherControls(prefix: String): List<ClockControl> = buildList {
        add(ClockControl("weather_contents") { index, count ->
            SettingsActionItem(stringResource(R.string.clock_weather_contents), null,
                index, count, "${prefix}_weather_contents", enabled = enabled, onClick = onWeatherContent)
        })
        if (ClockWeatherField.Icon in regions.weather.selected) add(ClockControl("weather_icons") { index, count ->
            SettingsActionItem(stringResource(R.string.clock_weather_icons), stringResource(R.string.clock_weather_icons_monochrome),
                index, count, "${prefix}_weather_icons", enabled = enabled, onClick = onWeatherIcons)
        })
    }
    fun batteryControls(prefix: String): List<ClockControl> = listOf(
        ClockControl("battery_content") { index, count ->
            ClockControlSegment(index, count, "${prefix}_battery_content") {
                ClockBatteryChoices(regions.battery.normalized(), enabled, "${prefix}_battery_content") { transform ->
                    onChange { current -> current.copy(battery = transform(current.battery.normalized())) }
                }
            }
        },
    )

    val primary = regions.primary
    val primaryControls = mutableListOf(ClockControl("elements") { index, count ->
        ClockControlSegment(index, count, "clock_primary_elements") {
            ClockElementButtons(ClockRegion.Primary, ClockElement.entries, setOfNotNull(primary), enabled,
                hapticsEnabled, onSelect = { element, checked ->
                    onChange { it.select(ClockRegion.Primary, element, checked) }
                })
        }
    })
    if (primary != null) {
        val face = regions.face(primary)
        val defaults = ClockRegions.defaultFace(primary)
        primaryControls += ClockControl("alignment") { index, count ->
            ClockControlSegment(index, count, "clock_primary_alignment") {
                ClockChoices(ClockAlignment.entries, regions.primaryAlignment, enabled, "clock_primary_alignment",
                    label = { it.label() }, onSelect = { alignment -> onChange { it.copy(primaryAlignment = alignment) } })
            }
        }
        // 2026-11-11 新增：横向微调滑块。
        //
        // 为什么放在对齐后面：对齐定大方向，滑块做微调，操作顺序和心智模型一致。
        // 范围 -120~120dp、步长 1：屏宽 360dp，±120 足够把时钟推到任意可见位置；
        // 超出这个范围只会移出可视区，没有意义。
        // 只在 fillWidth 路径生效（普通桌面），所以这里不依赖 fillWidth 参数。
        primaryControls += ClockControl("offset_x") { index, count ->
            ClockSlider(stringResource(R.string.clock_offset_x), regions.primaryOffsetXDp.roundToInt(), 0,
                -120..120, 1, index, count, "clock_primary_offset_x", enabled,
                valueLabel = if (regions.primaryOffsetXDp.roundToInt() == 0) null
                    else "%+d dp".format(regions.primaryOffsetXDp.roundToInt())) { value ->
                onChange { it.copy(primaryOffsetXDp = value.toFloat()) }
            }
        }
        if (primary == ClockElement.Clock) primaryControls += ClockControl("lines") { index, count ->
            ClockControlSegment(index, count, "clock_primary_lines") {
                ClockChoices(listOf(false, true), regions.stackedClock, enabled, "clock_primary_lines",
                    label = { stringResource(if (it) R.string.clock_two_lines else R.string.clock_single_line) },
                    onSelect = { stacked -> onChange { it.copy(stackedClock = stacked) } })
            }
        }
        if (primary == ClockElement.Clock && !regions.stackedClock) primaryControls += ClockControl("seconds") { index, count ->
            SettingsToggleItem(stringResource(R.string.clock_show_seconds), null, face.showSeconds,
                index, count, "clock_show_seconds", enabled) { value -> changeFace(primary) { it.copy(showSeconds = value) } }
        }
        if (primary == ClockElement.Clock && regions.stackedClock) {
            primaryControls += ClockControl("equal_width") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_equal_width_characters), null, face.equalWidthDigits,
                    index, count, "clock_equal_width_characters", enabled) { value ->
                    changeFace(primary) { it.copy(equalWidthDigits = value) }
                }
            }
            if (face.equalWidthDigits) primaryControls += ClockControl("center_characters") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_center_characters), null, face.centerDigits,
                    index, count, "clock_center_characters", enabled) { value ->
                    changeFace(primary) { it.copy(centerDigits = value) }
                }
            }
        }
        if (primary == ClockElement.Weekday || primary == ClockElement.Date) primaryControls += ClockControl("latin_letters") { index, count ->
            SettingsToggleItem(stringResource(R.string.clock_use_latin_letters), null, face.useLatinLetters,
                index, count, "clock_primary_latin_letters", enabled) { value ->
                changeFace(primary) { it.copy(useLatinLetters = value) }
            }
        }
        primaryControls += ClockControl("font") { index, count ->
            SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(face.fontId), index, count,
                "clock_font_selector", enabled = enabled) { onFont(primary) }
        }
        primaryControls += ClockControl("size") { index, count ->
            ClockSlider(stringResource(R.string.clock_size), face.size, defaults.size, 12..144, 1,
                index, count, "clock_size", enabled) { value -> changeFace(primary) { it.copy(size = value) } }
        }
        primaryControls += ClockControl("weight") { index, count ->
            ClockSlider(stringResource(R.string.clock_weight), face.weight, defaults.weight,
                100..face.maxWeight(ClockLayout.SingleLine), 100, index, count, "clock_weight", enabled) { value ->
                changeFace(primary) { it.copy(weight = value) }
            }
        }
        primaryControls += ClockControl("spacing") { index, count ->
            ClockSlider(stringResource(if (primary == ClockElement.Clock) R.string.clock_digit_spacing else R.string.clock_letter_spacing),
                face.letterSpacing, defaults.letterSpacing, -16..16, 1, index, count, "clock_letter_spacing", enabled) { value ->
                changeFace(primary) { it.copy(letterSpacing = value) }
            }
        }
        if (primary == ClockElement.Clock) primaryControls += ClockControl("hour_minute_spacing") { index, count ->
            ClockSlider(stringResource(R.string.clock_hour_minute_spacing), face.hourMinuteSpacing, defaults.hourMinuteSpacing,
                -16..64, 1, index, count, "clock_hour_minute_spacing", enabled) { value ->
                changeFace(primary) { it.copy(hourMinuteSpacing = value) }
            }
        }
        primaryControls += ClockControl("shadow") { index, count ->
            ClockSlider(stringResource(R.string.clock_font_shadow), face.fontShadow, defaults.fontShadow,
                0..24, 1, index, count, "clock_font_shadow", enabled) { value -> changeFace(primary) { it.copy(fontShadow = value) } }
        }
        if (primary == ClockElement.Clock) {
            primaryControls += ClockControl("leading_zero") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_show_leading_zero), null, face.showLeadingZero,
                    index, count, "clock_show_leading_zero", enabled) { value -> changeFace(primary) { it.copy(showLeadingZero = value) } }
            }
            primaryControls += ClockControl("separation") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_separate_digits), stringResource(R.string.clock_separate_digits_summary),
                    face.separateDigits, index, count, "clock_separate_digits", enabled) { value ->
                    changeFace(primary) { it.copy(separateDigits = value) }
                }
            }
            if (!regions.stackedClock) primaryControls += ClockControl("colon") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_show_colon), stringResource(R.string.clock_show_colon_summary),
                    face.showColon, index, count, "clock_show_colon", enabled) { value -> changeFace(primary) { it.copy(showColon = value) } }
            }
        }
        if (primary == ClockElement.Battery) primaryControls += batteryControls("clock_primary")
        if (primary == ClockElement.Weather) primaryControls += weatherControls("clock_primary")
    }
    group(ClockRegion.Primary, primaryControls)

    listOf(ClockRegion.Secondary, ClockRegion.Tertiary).forEach { region ->
        val row = if (region == ClockRegion.Secondary) regions.secondary else regions.tertiary
        val prefix = "clock_${region.name.lowercase()}"
        fun changeRow(transform: (ClockRegionRow) -> ClockRegionRow) = onChange { current ->
            if (region == ClockRegion.Secondary) current.copy(secondary = transform(current.secondary))
            else current.copy(tertiary = transform(current.tertiary))
        }
        val controls = mutableListOf(ClockControl("elements") { index, count ->
            ClockControlSegment(index, count, "${prefix}_elements") {
                ClockElementButtons(region, row.order, row.selected, enabled, hapticsEnabled,
                    disabledElements = setOfNotNull(primary),
                    centered = row.centered,
                    onSelect = { element, checked ->
                        onChange { current ->
                            if (element != current.primary) current.select(region, element, checked) else current
                        }
                    },
                    onReorder = { order -> changeRow { it.copy(order = order) } })
            }
        })
        if (row.selected.isNotEmpty()) controls += ClockControl("centered") { index, count ->
            SettingsToggleItem(stringResource(R.string.grace_button_align_center), null, row.centered,
                index, count, "${prefix}_centered", enabled) { value -> changeRow { it.copy(centered = value) } }
        }
        // 2026-11-11 新增：次要/第三区域的横向微调，两者各存各的值。
        if (row.selected.isNotEmpty()) controls += ClockControl("offset_x") { index, count ->
            ClockSlider(stringResource(R.string.clock_offset_x), row.offsetXDp.roundToInt(), 0,
                -120..120, 1, index, count, "${prefix}_offset_x", enabled,
                valueLabel = if (row.offsetXDp.roundToInt() == 0) null
                    else "%+d dp".format(row.offsetXDp.roundToInt())) { value ->
                changeRow { it.copy(offsetXDp = value.toFloat()) }
            }
        }
        if (ClockElement.Clock in row.selected) controls += ClockControl("seconds") { index, count ->
            SettingsToggleItem(stringResource(R.string.clock_show_seconds), null, row.showSeconds,
                index, count, "${prefix}_show_seconds", enabled) { value -> changeRow { it.copy(showSeconds = value) } }
        }
        if (row.selected.isNotEmpty()) {
            val defaults = ClockRowTypography()
            controls += ClockControl("font") { index, count ->
                SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(row.typography.fontId), index, count,
                    "${prefix}_font_selector", enabled = enabled) { onRegionFont(region) }
            }
            controls += ClockControl("size") { index, count ->
                ClockSlider(stringResource(R.string.clock_size), row.typography.size, defaults.size, 8..72, 1,
                    index, count, "${prefix}_size", enabled) { value ->
                    changeRow { it.copy(typography = it.typography.copy(size = value)) }
                }
            }
            controls += ClockControl("weight") { index, count ->
                ClockSlider(stringResource(R.string.clock_weight), row.typography.weight, defaults.weight, 100..900, 100,
                    index, count, "${prefix}_weight", enabled) { value ->
                    changeRow { it.copy(typography = it.typography.copy(weight = value)) }
                }
            }
            controls += ClockControl("shadow") { index, count ->
                ClockSlider(stringResource(R.string.clock_font_shadow), row.typography.fontShadow, defaults.fontShadow, 0..24, 1,
                    index, count, "${prefix}_font_shadow", enabled) { value ->
                    changeRow { it.copy(typography = it.typography.copy(fontShadow = value)) }
                }
            }
        }
        if (ClockElement.Clock in row.selected) {
            val face = regions.face(ClockElement.Clock)
            controls += ClockControl("fixed_clock_width") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_fixed_width), null, row.fixedClockWidth,
                    index, count, "${prefix}_fixed_clock_width", enabled) { value ->
                    changeRow { it.copy(fixedClockWidth = value) }
                }
            }
            controls += ClockControl("leading_zero") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_show_leading_zero), null, face.showLeadingZero,
                    index, count, "${prefix}_show_leading_zero", enabled) { value ->
                    changeFace(ClockElement.Clock) { it.copy(showLeadingZero = value) }
                }
            }
            controls += ClockControl("colon") { index, count ->
                SettingsToggleItem(stringResource(R.string.clock_show_colon), stringResource(R.string.clock_show_colon_summary),
                    face.showColon, index, count, "${prefix}_show_colon", enabled) { value ->
                    changeFace(ClockElement.Clock) { it.copy(showColon = value) }
                }
            }
        }
        if (ClockElement.Weekday in row.selected || ClockElement.Date in row.selected) controls += ClockControl("latin_letters") { index, count ->
            SettingsToggleItem(stringResource(R.string.clock_use_latin_letters), null, row.useLatinLetters,
                index, count, "${prefix}_latin_letters", enabled) { value -> changeRow { it.copy(useLatinLetters = value) } }
        }
        if (ClockElement.Battery in row.selected) controls += batteryControls(prefix)
        if (ClockElement.Weather in row.selected) controls += weatherControls(prefix)
        group(region, controls)
    }

    val agenda = regions.agenda
    fun changeAgenda(transform: (ClockAgenda) -> ClockAgenda) = onChange { current ->
        current.copy(agenda = transform(current.agenda))
    }
    val agendaControls = mutableListOf(ClockControl("contents") { index, count ->
        SettingsActionItem(stringResource(R.string.clock_agenda_contents), null,
            index, count, "clock_agenda_contents", enabled = enabled, onClick = onAgendaContent)
    })
    agendaControls += ClockControl("event_count") { index, count ->
        ClockSlider(stringResource(R.string.clock_agenda_event_count), agenda.eventCount, 1, 0..9, 1,
            index, count, "clock_agenda_event_count", enabled,
            valueLabel = if (agenda.eventCount == 0) stringResource(R.string.clock_agenda_unlimited) else agenda.eventCount.toString(),
            discrete = true) { value ->
            changeAgenda { it.copy(eventCount = value) }
        }
    }
    if (agenda.selected.isNotEmpty()) {
        val defaults = ClockRowTypography()
        agendaControls += ClockControl("alignment") { index, count ->
            ClockControlSegment(index, count, "clock_agenda_alignment") {
                ClockChoices(ClockAlignment.entries, agenda.alignment, enabled, "clock_agenda_alignment",
                    label = { it.label() }, onSelect = { alignment -> changeAgenda { it.copy(alignment = alignment) } })
            }
        }
        // 2026-11-11 新增：日程区域横向微调。
        agendaControls += ClockControl("offset_x") { index, count ->
            ClockSlider(stringResource(R.string.clock_offset_x), agenda.offsetXDp.roundToInt(), 0,
                -120..120, 1, index, count, "clock_agenda_offset_x", enabled,
                valueLabel = if (agenda.offsetXDp.roundToInt() == 0) null
                    else "%+d dp".format(agenda.offsetXDp.roundToInt())) { value ->
                changeAgenda { it.copy(offsetXDp = value.toFloat()) }
            }
        }
        agendaControls += ClockControl("font") { index, count ->
            SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(agenda.typography.fontId), index, count,
                "clock_agenda_font_selector", enabled = enabled) { onRegionFont(ClockRegion.Agenda) }
        }
        agendaControls += ClockControl("size") { index, count ->
            ClockSlider(stringResource(R.string.clock_size), agenda.typography.size, defaults.size, 8..72, 1,
                index, count, "clock_agenda_size", enabled) { value ->
                changeAgenda { it.copy(typography = it.typography.copy(size = value)) }
            }
        }
        agendaControls += ClockControl("weight") { index, count ->
            ClockSlider(stringResource(R.string.clock_weight), agenda.typography.weight, defaults.weight, 100..900, 100,
                index, count, "clock_agenda_weight", enabled) { value ->
                changeAgenda { it.copy(typography = it.typography.copy(weight = value)) }
            }
        }
        agendaControls += ClockControl("shadow") { index, count ->
            ClockSlider(stringResource(R.string.clock_font_shadow), agenda.typography.fontShadow, defaults.fontShadow, 0..24, 1,
                index, count, "clock_agenda_font_shadow", enabled) { value ->
                changeAgenda { it.copy(typography = it.typography.copy(fontShadow = value)) }
            }
        }
    }
    if (ClockAgendaField.Date in agenda.selected) agendaControls += ClockControl("latin_letters") { index, count ->
        SettingsToggleItem(stringResource(R.string.clock_use_latin_letters), null, agenda.useLatinLetters,
            index, count, "clock_agenda_latin_letters", enabled) { value -> changeAgenda { it.copy(useLatinLetters = value) } }
    }
    group(ClockRegion.Agenda, agendaControls)

    val tasks = regions.tasks
    fun changeTasks(transform: (ClockTasks) -> ClockTasks) = onChange { current ->
        current.copy(tasks = transform(current.tasks))
    }
    val taskControls = mutableListOf(ClockControl("contents") { index, count ->
        SettingsActionItem(stringResource(R.string.clock_task_contents), null,
            index, count, "clock_task_contents", enabled = enabled, onClick = onTaskContent)
    })
    taskControls += ClockControl("task_count") { index, count ->
        ClockSlider(stringResource(R.string.clock_task_count), tasks.taskCount, 1, 0..9, 1,
            index, count, "clock_task_count", enabled,
            valueLabel = if (tasks.taskCount == 0) stringResource(R.string.clock_agenda_unlimited) else tasks.taskCount.toString(),
            discrete = true) { value -> changeTasks { it.copy(taskCount = value) } }
    }
    if (tasks.selected.isNotEmpty()) {
        val defaults = ClockRowTypography()
        taskControls += ClockControl("alignment") { index, count ->
            ClockControlSegment(index, count, "clock_task_alignment") {
                ClockChoices(ClockAlignment.entries, tasks.alignment, enabled, "clock_task_alignment",
                    label = { it.label() }, onSelect = { alignment -> changeTasks { it.copy(alignment = alignment) } })
            }
        }
        // 2026-11-11 新增：任务区域横向微调。
        taskControls += ClockControl("offset_x") { index, count ->
            ClockSlider(stringResource(R.string.clock_offset_x), tasks.offsetXDp.roundToInt(), 0,
                -120..120, 1, index, count, "clock_task_offset_x", enabled,
                valueLabel = if (tasks.offsetXDp.roundToInt() == 0) null
                    else "%+d dp".format(tasks.offsetXDp.roundToInt())) { value ->
                changeTasks { it.copy(offsetXDp = value.toFloat()) }
            }
        }
        taskControls += ClockControl("font") { index, count ->
            SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(tasks.typography.fontId), index, count,
                "clock_task_font_selector", enabled = enabled) { onRegionFont(ClockRegion.Task) }
        }
        taskControls += ClockControl("size") { index, count ->
            ClockSlider(stringResource(R.string.clock_size), tasks.typography.size, defaults.size, 8..72, 1,
                index, count, "clock_task_size", enabled) { value ->
                changeTasks { it.copy(typography = it.typography.copy(size = value)) }
            }
        }
        taskControls += ClockControl("weight") { index, count ->
            ClockSlider(stringResource(R.string.clock_weight), tasks.typography.weight, defaults.weight, 100..900, 100,
                index, count, "clock_task_weight", enabled) { value ->
                changeTasks { it.copy(typography = it.typography.copy(weight = value)) }
            }
        }
        taskControls += ClockControl("shadow") { index, count ->
            ClockSlider(stringResource(R.string.clock_font_shadow), tasks.typography.fontShadow, defaults.fontShadow, 0..24, 1,
                index, count, "clock_task_font_shadow", enabled) { value ->
                changeTasks { it.copy(typography = it.typography.copy(fontShadow = value)) }
            }
        }
    }
    if (ClockTaskField.DueDate in tasks.selected || ClockTaskField.StartDate in tasks.selected) {
        taskControls += ClockControl("latin_letters") { index, count ->
            SettingsToggleItem(stringResource(R.string.clock_use_latin_letters), null, tasks.useLatinLetters,
                index, count, "clock_task_latin_letters", enabled) { value -> changeTasks { it.copy(useLatinLetters = value) } }
        }
    }
    group(ClockRegion.Task, taskControls)

    item("clock_layout_heading") { SettingsHeading(stringResource(R.string.clock_layout_section)) }
    val layoutCount = 5
    clockSetting("clock_region_order") {
        ClockControlSegment(0, layoutCount, "clock_region_order") {
            Column {
                Text(stringResource(R.string.clock_region_order))
                Spacer(Modifier.height(8.dp))
                ClockRegionOrder(regions.regionOrder, enabled, hapticsEnabled) { order ->
                    onChange { it.copy(regionOrder = order) }
                }
            }
        }
    }
    clockSetting("clock_region_spacing") {
        ClockSlider(stringResource(R.string.clock_region_spacing), regions.regionSpacing, ClockRegions().regionSpacing,
            0..64, 1, 1, layoutCount, "clock_region_spacing", enabled) { value ->
            onChange { it.copy(regionSpacing = value) }
        }
    }
    clockSetting("clock_rotate_above_primary") {
        SettingsToggleItem(stringResource(R.string.clock_rotate_above_primary), null, regions.rotateAbovePrimary,
            2, layoutCount, "clock_rotate_above_primary", enabled) { checked ->
            onChange { it.copy(rotateAbovePrimary = checked) }
        }
    }
    clockSetting("clock_rotate_primary") {
        SettingsToggleItem(stringResource(R.string.clock_rotate_primary), null, regions.rotatePrimary,
            3, layoutCount, "clock_rotate_primary", enabled) { checked ->
            onChange { it.copy(rotatePrimary = checked) }
        }
    }
    clockSetting("clock_rotate_all") {
        SettingsToggleItem(stringResource(R.string.clock_rotate_all), null, regions.rotateAll,
            4, layoutCount, "clock_rotate_all", enabled) { checked ->
            onChange { it.copy(rotateAll = checked) }
        }
    }
}

@Composable
private fun ClockControlSegment(index: Int, count: Int, tag: String, content: @Composable () -> Unit) {
    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.testTag(tag), content = content)
}

@Composable
private fun <T> ClockChoices(options: List<T>, selected: T, enabled: Boolean, tag: String,
    label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(checked = option == selected, onCheckedChange = { onSelect(option) }, enabled = enabled,
                shapes = connectedShapes(index, options.size),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag:$option").semantics { role = Role.RadioButton }) {
                Text(label(option))
            }
        }
    }
}

@Composable
private fun ClockBatteryChoices(battery: ClockBattery, enabled: Boolean, tag: String,
    onChange: ((ClockBattery) -> ClockBattery) -> Unit) {
    val count = ClockBatteryIconStyle.entries.size + 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ClockBatteryIconStyle.entries.forEachIndexed { index, style ->
            ToggleButton(checked = battery.showIcon && battery.iconStyle == style,
                onCheckedChange = { checked ->
                    onChange { current ->
                        when {
                            checked -> current.copy(showIcon = true, iconStyle = style)
                            current.iconStyle == style && current.showPercentage -> current.copy(showIcon = false)
                            else -> current
                        }
                    }
                }, enabled = enabled, shapes = connectedShapes(index, count),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag:$style")) {
                Icon(painterResource(clockBatteryIcon(style, 70, false)), style.label(), Modifier.size(24.dp))
            }
        }
        val percentageLabel = stringResource(R.string.clock_battery_show_percentage)
        ToggleButton(checked = battery.showPercentage,
            onCheckedChange = { checked ->
                onChange { current ->
                    if (checked || current.showIcon) current.copy(showPercentage = checked) else current
                }
            }, enabled = enabled, shapes = connectedShapes(count - 1, count),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag:Percentage")) {
            Icon(painterResource(R.drawable.ms_percent), percentageLabel, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ClockBatteryIconStyle.label(): String = stringResource(when (this) {
    ClockBatteryIconStyle.Android -> R.string.clock_battery_icon_android
    ClockBatteryIconStyle.AndroidFrame -> R.string.clock_battery_icon_android_frame
    ClockBatteryIconStyle.Bar -> R.string.clock_battery_icon_bar
})

/** The six slots stay mounted during a drag, so buttons and divider can cross each other. */
@Composable
private fun ClockElementButtons(
    region: ClockRegion,
    order: List<ClockElement?>,
    selected: Set<ClockElement>,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    disabledElements: Set<ClockElement> = emptySet(),
    centered: Boolean = false,
    onSelect: (ClockElement, Boolean) -> Unit,
    onReorder: ((List<ClockElement?>) -> Unit)? = null,
) {
    val latestOrder by rememberUpdatedState(order)
    val reorder by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val rects = remember(region) { mutableStateMapOf<String, Rect>() }
    var dragging by remember(region) { mutableStateOf<String?>(null) }
    var drop by remember(region) { mutableStateOf<ClockElementDrop?>(null) }
    var center by remember(region) { mutableFloatStateOf(0f) }
    var originalOrder by remember(region) { mutableStateOf<List<ClockElement?>?>(null) }
    val placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    fun feedback() { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun move(key: String, to: Int): Boolean {
        val current = latestOrder
        val from = current.indexOfFirst { (it?.name ?: "Divider") == key }
        if (!enabled || from < 0 || to !in current.indices || to == from) return false
        reorder?.invoke(current.toMutableList().apply { add(to, removeAt(from)) }) ?: return false
        feedback()
        return true
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically) {
        order.forEachIndexed { index, element ->
            val key = element?.name ?: "Divider"
            key(key) {
                val isDragging = dragging == key
                val released = drop?.takeIf { it.key == key }
                var consumedDrop by remember { mutableStateOf<ClockElementDrop?>(null) }
                // Keep the slots at their actual destinations for drag hit testing.
                // Animate only the visible center, initialized after the first layout.
                val position = rects[key]?.center?.x?.let { target ->
                    val animated = remember { Animatable(target) }
                    LaunchedEffect(target, isDragging, released, placementSpec) {
                        if (isDragging) {
                            animated.stop()
                        } else {
                            if (released != null && consumedDrop !== released) {
                                animated.snapTo(released.center)
                                consumedDrop = released
                            }
                            animated.animateTo(target, placementSpec)
                            if (released != null && drop === released) drop = null
                        }
                    }
                    animated
                }
                val currentPosition by rememberUpdatedState(position)
                val label = element?.label() ?: stringResource(R.string.clock_alignment_divider)
                val left = stringResource(R.string.clock_move_left)
                val right = stringResource(R.string.clock_move_right)
                val side = stringResource(when {
                    centered -> R.string.grace_button_align_center
                    index < order.indexOf(null) -> R.string.grace_button_align_left
                    else -> R.string.grace_button_align_right
                })
                val slot = if (element == null) Modifier.width(24.dp) else Modifier.weight(1f)
                Box(slot.heightIn(min = 48.dp).zIndex(if (isDragging || released != null) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInParent()
                        rects[key] = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
                    }
                    .then(if (onReorder != null) Modifier.pointerInput(region, key, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                rects[key]?.let {
                                    originalOrder = latestOrder
                                    center = currentPosition?.value ?: it.center.x
                                    drop = null
                                    dragging = key
                                    feedback()
                                }
                            },
                            onDragCancel = {
                                // Pointer-input disposal also cancels idle detectors when a
                                // LazyColumn row leaves composition. Only an active gesture
                                // may roll back its own draft; completed orders stay saved.
                                if (dragging == key) {
                                    val previous = originalOrder
                                    drop = ClockElementDrop(key, center)
                                    dragging = null
                                    originalOrder = null
                                    if (previous != null) reorder?.invoke(previous)
                                }
                            },
                            onDragEnd = {
                                if (dragging == key) {
                                    drop = ClockElementDrop(key, center)
                                    dragging = null
                                    originalOrder = null
                                }
                            },
                            onDrag = { change, amount ->
                                if (dragging == key) {
                                    change.consume()
                                    center += amount.x
                                    val from = latestOrder.indexOfFirst { (it?.name ?: "Divider") == key }
                                    val target = latestOrder.indices.minByOrNull {
                                        abs(center - (rects[latestOrder[it]?.name ?: "Divider"]?.center?.x ?: center))
                                    }
                                    if (target != null) {
                                        val targetCenter = rects[latestOrder[target]?.name ?: "Divider"]?.center?.x ?: center
                                        if ((target > from && amount.x > 0 && center >= targetCenter) ||
                                            (target < from && amount.x < 0 && center <= targetCenter)) move(key, target)
                                    }
                                }
                            },
                        )
                    } else Modifier), contentAlignment = Alignment.Center) {
                    val control = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .graphicsLayer {
                            val target = rects[key]?.center?.x ?: 0f
                            val release = drop?.takeIf { it.key == key }
                            val visible = when {
                                dragging == key -> center
                                release != null && consumedDrop !== release -> release.center
                                else -> position?.value ?: target
                            }
                            translationX = visible - target
                        }
                        .testTag("clock_region:${region.name}:$key")
                        .semantics(mergeDescendants = element == null) {
                            if (element == null) contentDescription = label
                            if (onReorder != null) {
                                if (element != null && element in selected) stateDescription = side
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(left) { move(key, index - 1) })
                                    if (index < order.lastIndex) add(CustomAccessibilityAction(right) { move(key, index + 1) })
                                }
                            }
                        }
                    if (element == null) Box(control, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(2.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
                    } else ToggleButton(checked = element in selected, onCheckedChange = { onSelect(element, it) },
                        enabled = enabled && element !in disabledElements,
                        shapes = connectedShapes(index, order.size), contentPadding = PaddingValues(0.dp), modifier = control) {
                        Icon(painterResource(element.icon()), label, Modifier.size(24.dp))
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun connectedShapes(index: Int, count: Int) = when (index) {
    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

@Composable
internal fun ClockRegion.label(): String = stringResource(when (this) {
    ClockRegion.Primary -> R.string.clock_primary_region
    ClockRegion.Secondary -> R.string.clock_secondary_region
    ClockRegion.Tertiary -> R.string.clock_tertiary_region
    ClockRegion.Agenda -> R.string.clock_agenda_region
    ClockRegion.Task -> R.string.clock_task_region
})

@Composable
private fun ClockAlignment.label(): String = stringResource(when (this) {
    ClockAlignment.Left -> R.string.grace_button_align_left
    ClockAlignment.Center -> R.string.grace_button_align_center
    ClockAlignment.Right -> R.string.grace_button_align_right
})

@Composable
private fun ClockElement.label(): String = stringResource(when (this) {
    ClockElement.Clock -> R.string.settings_clock
    ClockElement.Weekday -> R.string.clock_color_weekday
    ClockElement.Date -> R.string.clock_color_date_group
    ClockElement.Battery -> R.string.clock_color_battery
    ClockElement.Weather -> R.string.settings_weather
})

private fun ClockElement.icon(): Int = when (this) {
    ClockElement.Clock -> R.drawable.ms_schedule
    ClockElement.Weekday -> R.drawable.ms_view_week
    ClockElement.Date -> R.drawable.ms_calendar_month
    ClockElement.Battery -> R.drawable.ms_battery_android_5
    ClockElement.Weather -> R.drawable.ms_partly_cloudy_day
}
