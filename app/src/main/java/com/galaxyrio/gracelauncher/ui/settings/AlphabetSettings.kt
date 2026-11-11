@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.AlphabetAppearance
import com.galaxyrio.gracelauncher.data.AlphabetRailLayout
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AlphabetRail
import com.galaxyrio.gracelauncher.ui.drawer.AppListModel
import com.galaxyrio.gracelauncher.ui.home.rememberHeadphonesConnected
import com.galaxyrio.gracelauncher.ui.home.rememberHomeTime
import com.galaxyrio.gracelauncher.ui.theme.LocalFontLibrary
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.resolvedFontColor
import kotlin.math.roundToInt
import org.json.JSONObject

private val AlphabetAppearanceDraftSaver = Saver<AlphabetAppearance, String>(
    save = { it.json().toString() }, restore = { runCatching { AlphabetAppearance.decode(JSONObject(it)) }.getOrNull() },
)

@Composable
internal fun AlphabetSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onManageFonts: () -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val saved = uiState.settings.listAppearance.alphabet
    val defaults = remember { AlphabetAppearance() }
    var draft by rememberSaveable(saved, stateSaver = AlphabetAppearanceDraftSaver) { mutableStateOf(saved.normalized()) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val controls = rememberLazyListState()
    val enabled = LocalSettingsStorageState.current.canEdit
    val previewModel = rememberAlphabetPreviewModel(uiState)
    val letters = previewModel.letters
    val density = LocalDensity.current
    var viewportTop by remember { mutableFloatStateOf(0f) }
    // Measure before the scaffold takes out its app bar and navigation padding,
    // just like the desktop's full-height BoxWithConstraints.
    val viewportHeight = maxHeight.value.coerceAtLeast(1f)
    val railLayout = draft.layout(viewportHeight, letters.size + 1)
    val automaticTop = draft.copy(topPercent = null).layout(viewportHeight, letters.size + 1).topDp / viewportHeight * 100f
    val wallpaper = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, 100)
    val primary = MaterialTheme.colorScheme.primary
    val heroSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val heroBounds = remember(heroSpec) { BoundsTransform { _, _ -> heroSpec } }
    val heroShape = ListItemDefaults.segmentedShapes(0, 1).shape
    fun change(transform: (AlphabetAppearance) -> AlphabetAppearance) {
        if (!enabled) return
        draft = transform(draft).normalized()
        actions.updateSettings { current -> current.copy(listAppearance = current.listAppearance.copy(
            alphabet = transform(current.listAppearance.alphabet).normalized())) }
    }
    fun closeColor() {
        val color = draft.fontColor
        change { it.copy(fontColor = color) }
        dialog = null
    }
    BackHandler(dialog == "color") { closeColor() }
    if (dialog == "font") FontPickerDialog(uiState.settings.fontLibrary, draft.fontId,
        onSelect = { id -> change { it.copy(fontId = id) }; dialog = null },
        onManage = { dialog = null; onManageFonts() }, onDismiss = { dialog = null }, tag = "alphabet_font_picker")

    SettingsPreviewScaffold(uiState, stringResource(R.string.settings_alphabet), "alphabet_settings",
        { if (dialog == "color") closeColor() else onBack() },
        wallpaperNavigationBar = true,
        modifier = Modifier.onGloballyPositioned { viewportTop = with(density) { it.positionInWindow().y.toDp().value } }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // Leave a useful controls column on small phones, and enough preview width
            // for both the wave and its indicator. The right edge has no outer margin.
            val previewWidth = (maxWidth * .42f).coerceIn(160.dp, 180.dp)
            AlphabetPreview(uiState, draft, previewModel, railLayout, viewportTop, Modifier.align(AbsoluteAlignment.TopRight).width(previewWidth).fillMaxHeight()
                .padding(top = 8.dp, bottom = 8.dp + LocalSettingsBottomInset.current))
            SharedTransitionLayout(Modifier.fillMaxSize().absolutePadding(left = 16.dp, right = previewWidth + 8.dp, top = 8.dp).clipToBounds()) {
                AnimatedContent(dialog == "color", Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "alphabetColorHero") { colorOpen ->
                    val colorModifier = Modifier.sharedBounds(rememberSharedContentState("alphabet_color"), this,
                        boundsTransform = heroBounds, clipInOverlayDuringTransition = OverlayClip(heroShape))
                    if (colorOpen) {
                        IconDesignerColorPicker(stringResource(R.string.alphabet_letter_color), draft.fontColor,
                            primary.toArgb(), null,
                            onChange = { if (enabled && dialog == "color") draft = draft.copy(fontColor = it) },
                            onClose = { if (dialog == "color") closeColor() },
                            modifier = Modifier.padding(bottom = 8.dp).then(colorModifier), fallback = wallpaper.text.toArgb(),
                            fontColors = uiState.settings.fontColors)
                    } else LazyColumn(Modifier.fillMaxSize().clip(settingsPreviewControlsShape()).testTag("alphabet_controls"), state = controls,
                        contentPadding = PaddingValues(bottom = 24.dp + LocalSettingsBottomInset.current),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        item("font") {
                            SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(draft.fontId),
                                0, 11, "alphabet_font", enabled = enabled, onClick = { dialog = "font" })
                        }
                        item("color") {
                            SettingsActionItem(stringResource(R.string.alphabet_letter_color), fontColorSummary(draft.fontColor, onWallpaper = true),
                                1, 11, "alphabet_color", enabled = enabled, modifier = colorModifier, trailing = {
                                    Surface(Modifier.size(24.dp), shape = MaterialTheme.shapes.small,
                                        color = resolvedFontColor(draft.fontColor, onWallpaper = true)) {}
                                }, onClick = { dialog = "color" })
                        }
                        item("font_size") {
                            DesignerSegment(2, 11, "alphabet_font_size_control") {
                                DesignerSlider(stringResource(R.string.clock_size), draft.fontSize.toFloat(), defaults.fontSize.toFloat(),
                                    10f..28f, enabled, "alphabet_font_size", suffix = "sp",
                                    onFinished = { value -> change { it.copy(fontSize = value.roundToInt()) } }) {
                                    draft = draft.copy(fontSize = it.roundToInt())
                                }
                            }
                        }
                        // 2026-11-11 新增：透明度滑块。
                        // 放在字号后面，因为两者都是同一条视觉线（字的观感）。
                        // 范围 0~100，步长由 DesignerSlider 内部处理；默认 85 与旧版观感一致。
                        item("opacity") {
                            DesignerSegment(3, 11, "alphabet_opacity_control") {
                                DesignerSlider(stringResource(R.string.alphabet_opacity), draft.opacityPercent.toFloat(),
                                    defaults.opacityPercent.toFloat(), 0f..100f, enabled, "alphabet_opacity", suffix = "%",
                                    onFinished = { value -> change { it.copy(opacityPercent = value.roundToInt()) } }) {
                                    draft = draft.copy(opacityPercent = it.roundToInt())
                                }
                            }
                        }
                        item("shape") {
                            DesignerSegment(4, 11, "alphabet_shape") {
                                Text(stringResource(R.string.alphabet_indicator_shape))
                                val design = IconDesign(shape = draft.indicatorShape, cookieSides = draft.cookieSides,
                                    pebbleRoundness = draft.pebbleRoundness, squareCornerRadius = draft.squareCornerRadius)
                                val update: (IconDesign) -> Unit = { value -> change { it.copy(indicatorShape = value.shape,
                                    cookieSides = value.cookieSides, pebbleRoundness = value.pebbleRoundness, squareCornerRadius = value.squareCornerRadius) } }
                                DesignerShapeChoices(design, enabled, "alphabet", update)
                                DesignerShapeAdjustment(design, IconDesign(), enabled, "alphabet", onFinished = update, includeAspectRatio = false) { value ->
                                    draft = draft.copy(indicatorShape = value.shape, cookieSides = value.cookieSides,
                                        pebbleRoundness = value.pebbleRoundness, squareCornerRadius = value.squareCornerRadius)
                                }
                            }
                        }
                        item("top") {
                            DesignerSegment(5, 11, "alphabet_top_control") {
                                if (draft.topPercent == null) Text(
                                    stringResource(R.string.alphabet_automatic_spacing, AlphabetAppearance.DefaultStepDp.roundToInt()),
                                    Modifier.testTag("alphabet_top_automatic"), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DesignerSlider(stringResource(R.string.alphabet_top), draft.topPercent?.toFloat() ?: automaticTop, automaticTop,
                                    0f..99f, enabled, "alphabet_top", onFinished = { value ->
                                        val bottom = draft.bottomPercent
                                        change { it.copy(topPercent = value.roundToInt(), bottomPercent = bottom) }
                                    }, resetEnabled = draft.topPercent != null, onReset = { change { it.copy(topPercent = null) } }) {
                                    draft = draft.copy(topPercent = it.roundToInt()).normalized()
                                }
                            }
                        }
                        item("bottom") {
                            DesignerSegment(6, 11, "alphabet_bottom_control") {
                                DesignerSlider(stringResource(R.string.alphabet_bottom), draft.bottomPercent.toFloat(),
                                    maxOf(defaults.bottomPercent, (draft.topPercent ?: 0) + 1).toFloat(),
                                    ((draft.topPercent ?: 0) + 1f)..100f, enabled, "alphabet_bottom",
                                    onFinished = { value -> change { it.copy(bottomPercent = value.roundToInt()) } }) {
                                    draft = draft.copy(bottomPercent = it.roundToInt()).normalized()
                                }
                            }
                        }
                        item("free") {
                            SettingsToggleItem(stringResource(R.string.alphabet_free_movement), null,
                                draft.freeMovement, 7, 11, "alphabet_free_movement", enabled = enabled) { value -> change { it.copy(freeMovement = value) } }
                        }
                        item("touch_width") {
                            DesignerSegment(8, 11, "alphabet_touch_width_control") {
                                DesignerSlider(stringResource(R.string.alphabet_touch_width), draft.rightTouchWidth.toFloat(),
                                    defaults.rightTouchWidth.toFloat(), 28f..56f, enabled, "alphabet_touch_width", suffix = "dp",
                                    onFinished = { value -> change { it.copy(rightTouchWidth = value.roundToInt()) } }) {
                                    draft = draft.copy(rightTouchWidth = it.roundToInt()).normalized()
                                }
                            }
                        }
                        item("left") {
                            SettingsToggleItem(stringResource(R.string.list_left_alphabet), null,
                                uiState.settings.listAppearance.leftAlphabet, 9, 11, "list_left_alphabet", enabled = enabled) { value ->
                                actions.updateSettings { current -> current.copy(listAppearance = current.listAppearance.copy(leftAlphabet = value)) }
                            }
                        }
                        item("show_in") {
                            DesignerSegment(10, 11, "alphabet_show_in") {
                                Text(stringResource(R.string.alphabet_show_in))
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                    ToggleButton(checked = draft.showsOnHome(uiState.settings.hideAlphabet),
                                        onCheckedChange = { value -> change { it.copy(showOnHome = value) } }, enabled = enabled,
                                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("alphabet_show_home"),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                                        Text(stringResource(R.string.settings_home_screen))
                                    }
                                    ToggleButton(checked = draft.showInAppList,
                                        onCheckedChange = { value -> change { it.copy(showInAppList = value) } }, enabled = enabled,
                                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("alphabet_show_app_list"),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                                        Text(stringResource(R.string.settings_folder_app_list))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphabetPreview(uiState: LauncherUiState, draft: AlphabetAppearance, model: AppListModel, layout: AlphabetRailLayout,
    viewportTop: Float, modifier: Modifier) {
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, 100)
    val settings = uiState.settings
    val density = LocalDensity.current
    var previewTop by remember { mutableFloatStateOf(0f) }
    var positioned by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showTouchArea by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.testTag("alphabet_preview").onGloballyPositioned {
        previewTop = with(density) { it.positionInWindow().y.toDp().value }
        positioned = true
    }.clipToBounds()) {
        CompositionLocalProvider(LocalLauncherAppearance provides appearance, LocalFontLibrary provides settings.fontLibrary) {
            // A window into the desktop, not another screen with a shorter coordinate
            // system. Preserve actual letter spacing, glyph sizes and indicator geometry.
            if (positioned) AlphabetRail(model.letters, selected, layout.heightDp.dp,
                onLetterSelected = { selected = it }, alphabet = draft,
                categorySections = model.categorySections,
                showRightTouchArea = showTouchArea,
                topOffset = (viewportTop + layout.topDp - previewTop).dp, touchHeight = maxHeight,
                modifier = Modifier.align(AbsoluteAlignment.TopRight))
        }
        FilledTonalIconToggleButton(checked = showTouchArea, onCheckedChange = { showTouchArea = it },
            modifier = Modifier.align(AbsoluteAlignment.BottomLeft).padding(8.dp).testTag("alphabet_touch_area_toggle")) {
            Icon(painterResource(R.drawable.ms_touch_app), stringResource(R.string.alphabet_touch_area), Modifier.size(24.dp))
        }
    }
}

@Composable
private fun rememberAlphabetPreviewModel(uiState: LauncherUiState): AppListModel {
    val settings = uiState.settings
    val profilesReady = !uiState.isLoadingSettings && !uiState.settingsLoadFailed
    val privateFolder = uiState.privateFolder.takeIf { settings.privateSpace.enabled && uiState.privateSpace.supported &&
        settings.privateSpace.display != PrivateSpaceDisplay.NormalApp && profilesReady }
    val workFolder = uiState.workFolder.takeIf { settings.workProfile.enabled && uiState.workProfiles.isNotEmpty() &&
        settings.workProfile.display != PrivateSpaceDisplay.NormalApp && profilesReady }
    val apps = uiState.appListApps
    val recentFolder = uiState.recentlyInstalledFolder(stringResource(R.string.recently_installed))
        .takeIf { settings.listAppearance.showRecentlyInstalled }
    val categories = uiState.appCategories
    val members = remember(uiState, categories) {
        categories.associate { category -> category.key to uiState.categoryItems(category).mapNotNull(uiState::findItem) }
    }
    val now by rememberHomeTime()
    val headphones by rememberHeadphonesConnected()
    val time = now.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val minute = time.hour * 60 + time.minute
    return remember(apps, uiState.folders, privateFolder, workFolder, recentFolder, settings.listAppearance.appList, categories, members, headphones, minute) {
        AppListModel(apps, uiState.folders, privateFolder, recentlyInstalledFolder = recentFolder, workFolder = workFolder,
            appList = settings.listAppearance.appList, categories = categories, categoryItems = members,
            headphones = headphones, minuteOfDay = minute)
    }
}
