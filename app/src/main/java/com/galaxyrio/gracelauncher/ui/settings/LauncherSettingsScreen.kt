@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.GraceButtonTarget
import com.galaxyrio.gracelauncher.ui.components.ShortcutPickerScreen
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.SystemLauncherTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SettingsPage {
    Root, Productivity, Clock, ClockStyle, Calendar, CalendarSources, CalendarApp, Weather, Themes, Advanced, Language, About, HiddenApps, Folders, FolderEditor,
    Changelog, Licenses, AppLicense, IconPacks, IconDesigner, IconDesignerApp, PrivateSpace, PrivateSpaceEditor, Search,
    MediaPlayer, GraceButton, GraceAction, GraceApp, GraceShortcut, GraceFolder, Gestures, WorkProfile, WorkProfileEditor,
    LabGraceButton, LabGesture, LabPopup, LabAccessibility, LabDismiss, Fonts, FontColors, ListAppearance, Alphabet, ShortcutsFolders, AppStacks,
    GraceButtonAppearance, GraceButtonPosition, AgendaAppearance, SearchAppearance, MediaPlayerAppearance,
    WidgetScreen, WidgetScreenEditor, Homepage, AppList, FeedScreen, StatusScreen, DigitalWellbeing, TabletsFoldables,
    Wallpaper, WallpaperDesigner,
    AppCategories, CategoryEditor, TasksOrg, TaskLists, Backup,
}

/** The window's existing system-bar owner follows the active settings surface. */
internal enum class SettingsSystemBarBackground { Surface, Wallpaper, WallpaperWithBottomScrim, WallpaperWithTopScrim }
internal val LocalSettingsSystemBarBackground = staticCompositionLocalOf<(SettingsSystemBarBackground) -> Unit> { {} }

/** Navigation owns each page's saved state and seekable predictive-back transition. */
@Composable
fun LauncherSettingsScreen(
    uiState: LauncherUiState,
    actions: LauncherActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: String? = null,
    initialFolderId: String? = null,
    handleRootBack: Boolean = true,
    initialIconDesignerApp: LauncherApp? = null,
    closePrivateSpaceOnDispose: Boolean = true,
    initialCategoryId: String? = null,
) {
    val navController = rememberNavController()
    val closePrivateSpace by rememberUpdatedState(actions.closePrivateSpace)
    DisposableEffect(closePrivateSpaceOnDispose) { onDispose { if (closePrivateSpaceOnDispose) closePrivateSpace() } }
    val startDestination = remember(initialPage, initialFolderId, initialCategoryId) {
        when {
            initialFolderId != null -> "FolderEditor/${Uri.encode(initialFolderId)}"
            initialCategoryId != null -> "CategoryEditor/${Uri.encode(initialCategoryId)}"
            initialPage == "folders" -> SettingsPage.Folders.name
            initialPage == "Favorites" -> SettingsPage.Homepage.name
            initialPage != null -> SettingsPage.entries.firstOrNull { it.name == initialPage }?.name ?: SettingsPage.Root.name
            else -> SettingsPage.Root.name
        }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.id) {
        // NavHost's predictive back can pop the editor without invoking its app-bar callback.
        if (currentEntry?.destination?.route == SettingsPage.PrivateSpace.name) closePrivateSpace()
    }
    val canPop = currentEntry != null && navController.previousBackStackEntry != null
    val scope = rememberCoroutineScope()
    val rootExit = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)
    val distance = with(LocalDensity.current) { 30.dp.roundToPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val closeRoot: () -> Unit = {
        if (!closing) {
            if (!handleRootBack) latestOnBack() else {
                closing = true
                scope.launch {
                    rootExit.animateTo(1f, tween(300))
                    latestOnBack()
                }
            }
        }
    }

    // Embedded settings reveal the retained desktop. The standalone SettingsActivity
    // leaves its root callback disabled, preserving Android's cross-task animation.
    PredictiveBackHandler(enabled = handleRootBack && !canPop && !closing) { events ->
        try {
            events.collect { rootExit.snapTo(it.progress) }
            closing = true
            rootExit.animateTo(1f, tween(180))
            latestOnBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { rootExit.animateTo(0f, tween(180)) }
            closing = false
            throw cancelled
        }
    }

    val storage = SettingsStorageState(
        uiState.isLoadingSettings, uiState.settingsLoadFailed, uiState.settingsSaveFailed,
    )
    val reportSystemBarBackground by rememberUpdatedState(LocalSettingsSystemBarBackground.current)
    DisposableEffect(Unit) { onDispose { reportSystemBarBackground(SettingsSystemBarBackground.Surface) } }
    MaterialTheme(typography = if (uiState.settings.applyFontToSettings) LocalLauncherTypography.current else SystemLauncherTypography) {
     CompositionLocalProvider(LocalSettingsStorageState provides storage) {
      NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize().testTag("settings_navigation").graphicsLayer {
            alpha = 1f - rootExit.value
            translationX = distance * direction * rootExit.value
        }.background(if (currentEntry?.destination?.route in listOf(SettingsPage.ClockStyle.name, SettingsPage.IconDesigner.name,
            SettingsPage.ListAppearance.name, SettingsPage.Alphabet.name, SettingsPage.GraceButtonAppearance.name, SettingsPage.GraceButtonPosition.name,
            SettingsPage.MediaPlayerAppearance.name))
            Color.Transparent else MaterialTheme.colorScheme.surfaceContainer),
        enterTransition = { settingsEnter(distance) },
        exitTransition = { settingsExit(distance) },
        popEnterTransition = { settingsEnter(distance, back = true) },
        popExitTransition = { settingsExit(distance, back = true) },
      ) {
        SettingsPage.entries.forEach { page ->
            val isEditor = page == SettingsPage.FolderEditor
            val isGraceAction = page in listOf(SettingsPage.GraceAction, SettingsPage.GraceApp, SettingsPage.GraceShortcut, SettingsPage.GraceFolder)
            val isCategoryEditor = page == SettingsPage.CategoryEditor
            composable(
                route = when { isEditor -> "FolderEditor/{folderId}"; isCategoryEditor -> "CategoryEditor/{categoryId}"; isGraceAction -> "${page.name}/{gesture}?home={home}"; else -> page.name },
                arguments = when {
                    isEditor -> listOf(navArgument("folderId") { type = NavType.StringType })
                    isCategoryEditor -> listOf(navArgument("categoryId") { type = NavType.StringType })
                    isGraceAction -> listOf(navArgument("gesture") { type = NavType.StringType }, navArgument("home") { type = NavType.BoolType; defaultValue = false })
                    else -> emptyList()
                },
            ) { entry ->
                // Inline editors can switch to an opaque source picker without changing their route.
                // Retain each entry's surface separately so outgoing transitions cannot override it.
                var systemBarBackground by remember(entry) { mutableStateOf(SettingsSystemBarBackground.Surface) }
                val activeEntry = currentEntry?.id == entry.id
                val currentSystemBarBackground = systemBarBackground
                SideEffect { if (activeEntry) reportSystemBarBackground(currentSystemBarBackground) }
                // Ignore double taps and clicks on an outgoing/predictively revealed page.
                fun isCurrent() = navController.currentBackStackEntry === entry &&
                    entry.lifecycle.currentState == Lifecycle.State.RESUMED && !closing
                val navigate: (SettingsPage) -> Unit = { destination ->
                    if (isCurrent()) navController.navigate(destination.name) { launchSingleTop = true }
                }
                val back: () -> Unit = {
                    if (isCurrent()) {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                        else closeRoot()
                    }
                }
                CompositionLocalProvider(LocalSettingsSystemBarBackground provides { systemBarBackground = it },
                    LocalOpenFontColors provides { navigate(SettingsPage.FontColors) },
                    LocalOpenDigitalWellbeing provides { navigate(SettingsPage.DigitalWellbeing) }) {
                // Never initialize an editable draft before Room's first snapshot.
                if (!storage.canEdit) {
                    SettingsScaffold(stringResource(R.string.settings_title), "settings_storage_blocked", back) { }
                } else when (page) {
                    SettingsPage.Root -> SettingsHome(uiState.isDefaultHome, actions.requestDefaultHome,
                        onOpenHome = { if (isCurrent()) actions.openHome() }, onBack = back, navigate = navigate)
                    SettingsPage.Productivity -> ProductivitySettings(uiState, actions, back, navigate)
                    SettingsPage.AppCategories -> AppCategoriesSettings(uiState, actions, back, onEdit = { id ->
                        if (isCurrent()) navController.navigate("CategoryEditor/${Uri.encode(id)}") { launchSingleTop = true }
                    })
                    SettingsPage.CategoryEditor -> AppCategoryEditorSettings(entry.arguments?.getString("categoryId"), uiState, actions, back)
                    SettingsPage.DigitalWellbeing -> DigitalWellbeingSettingsScreen(uiState, actions, back)
                    SettingsPage.TabletsFoldables -> SettingsPlaceholder(stringResource(R.string.settings_tablets_foldables), "tablets_foldables_settings", back)
                    SettingsPage.Homepage -> com.galaxyrio.gracelauncher.ui.overlays.HomepageEditorScreen(
                        uiState, actions, actions.toggleFavorite, actions.reorderFavorites, onDone = back)
                    SettingsPage.AppList -> com.galaxyrio.gracelauncher.ui.overlays.AppListEditorScreen(uiState, actions, back)
                    SettingsPage.FeedScreen -> SettingsPlaceholder(stringResource(R.string.settings_feed_screen), "feed_screen_settings", back)
                    SettingsPage.StatusScreen -> SettingsPlaceholder(stringResource(R.string.settings_status_screen), "status_screen_settings", back)
                    SettingsPage.Clock -> ClockSettings(uiState, actions, back) { navigate(SettingsPage.ClockStyle) }
                    SettingsPage.Search -> SearchSettingsScreen(uiState, actions, back)
                    SettingsPage.ShortcutsFolders -> ShortcutsFoldersSettingsScreen(uiState, actions, back)
                    SettingsPage.WidgetScreen -> WidgetScreenSettingsPage(uiState, actions, back) { navigate(SettingsPage.WidgetScreenEditor) }
                    SettingsPage.WidgetScreenEditor -> com.galaxyrio.gracelauncher.ui.overlays.WidgetScreenEditor(uiState, actions, back)
                    SettingsPage.MediaPlayer -> MediaPlayerSettings(uiState, actions, back) { navigate(SettingsPage.MediaPlayerAppearance) }
                    SettingsPage.GraceButton, SettingsPage.Gestures -> GraceButtonSettingsScreen(uiState, actions, back,
                        home = page == SettingsPage.Gestures, onAppearance = { navigate(SettingsPage.GraceButtonAppearance) }) { gesture ->
                        if (isCurrent()) navController.navigate("${SettingsPage.GraceAction.name}/${gesture.name}?home=${page == SettingsPage.Gestures}") { launchSingleTop = true }
                    }
                    SettingsPage.GraceAction -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        GraceButtonActionSettings(gesture, uiState, actions, back, home) { action ->
                            val destination = when (action) {
                                GraceButtonAction.Folder -> SettingsPage.GraceFolder
                                GraceButtonAction.Shortcut -> SettingsPage.GraceShortcut
                                else -> SettingsPage.GraceApp
                            }
                            if (isCurrent()) navController.navigate("${destination.name}/${gesture.name}?home=$home") { launchSingleTop = true }
                        }
                    }
                    SettingsPage.GraceApp -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        AppSelectionSettings(uiState, actions, back, title = stringResource(R.string.grace_button_open_app),
                            tag = "grace_button_app", selectedKey = target.itemKey.takeIf { target.action == GraceButtonAction.App }, excludeOwnApp = false,
                            onSelect = { key -> if (isCurrent() && key != null) {
                                actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture, GraceButtonTarget(GraceButtonAction.App, key)) } }
                                back()
                            } })
                    }
                    SettingsPage.GraceFolder -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        val selectedFolder = uiState.folders.firstOrNull { target.action == GraceButtonAction.Folder && it.key == target.itemKey }
                        FolderSettings(uiState, actions, back, selectedIds = setOfNotNull(selectedFolder?.id), singleChoice = true,
                            onToggle = { id ->
                                val folder = uiState.folders.firstOrNull { it.id == id }
                                if (isCurrent() && folder != null) {
                                    actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture,
                                        GraceButtonTarget(GraceButtonAction.Folder, folder.key)) } }
                                    back()
                                }
                            },
                            onEdit = { id -> if (isCurrent()) navController.navigate("FolderEditor/${Uri.encode(id)}") })
                    }
                    SettingsPage.GraceShortcut -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        val pickerScope = rememberCoroutineScope()
                        var saving by remember { mutableStateOf(false) }
                        ShortcutPickerScreen(uiState, actions, setOfNotNull(target.itemKey.takeIf { target.action == GraceButtonAction.Shortcut }),
                            back, singleChoice = true, busy = saving, onSelect = { app ->
                                saving = true
                                pickerScope.launch {
                                    try {
                                        if (actions.rememberShortcut(app) && isCurrent()) {
                                            actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture,
                                                GraceButtonTarget(GraceButtonAction.Shortcut, app.key)) } }
                                            back()
                                        }
                                    } finally { saving = false }
                                }
                            })
                    }
                    SettingsPage.Weather -> WeatherSettings(uiState, actions, back)
                    SettingsPage.Themes -> ThemeSettings(uiState, actions, back,
                        onClockStyle = { navigate(SettingsPage.ClockStyle) }, onIconPacks = { navigate(SettingsPage.IconPacks) },
                        onFonts = { navigate(SettingsPage.Fonts) }, onListAppearance = { navigate(SettingsPage.ListAppearance) },
                        onWallpaper = { navigate(SettingsPage.Wallpaper) },
                        onAlphabet = { navigate(SettingsPage.Alphabet) }, onGraceButton = { navigate(SettingsPage.GraceButtonAppearance) },
                        onAgenda = { navigate(SettingsPage.AgendaAppearance) }, onSearch = { navigate(SettingsPage.SearchAppearance) },
                        onMediaPlayer = { navigate(SettingsPage.MediaPlayerAppearance) })
                    SettingsPage.GraceButtonAppearance -> IconDesignerSettings(uiState, actions, GraceButtonIcon.key, back,
                        onChooseApp = {}, onSelectApp = {}, onPositionAndSize = { navigate(SettingsPage.GraceButtonPosition) })
                    SettingsPage.GraceButtonPosition -> GraceButtonPositionSettings(uiState, actions, back)
                    SettingsPage.AgendaAppearance -> SettingsPlaceholder(stringResource(R.string.settings_agenda), "agenda_appearance", back)
                    SettingsPage.SearchAppearance -> SettingsPlaceholder(stringResource(R.string.settings_search), "search_appearance", back)
                    SettingsPage.MediaPlayerAppearance -> MediaPlayerAppearanceSettings(uiState, actions, back)
                    SettingsPage.ClockStyle -> ClockStyleSettings(uiState, actions, back) { navigate(SettingsPage.Fonts) }
                    SettingsPage.Fonts -> FontSettings(uiState, actions, back) { navigate(SettingsPage.FontColors) }
                    SettingsPage.FontColors -> FontColorSettings(uiState, actions, back)
                    SettingsPage.Wallpaper -> WallpaperSettings(uiState, actions, back,
                        onDesigner = { navigate(SettingsPage.WallpaperDesigner) },
                        onChooseWallpaper = { if (isCurrent()) actions.chooseWallpaper() })
                    SettingsPage.WallpaperDesigner -> SettingsPlaceholder(stringResource(R.string.settings_wallpaper_designer),
                        "wallpaper_designer", back)
                    SettingsPage.ListAppearance -> ListAppearanceSettings(uiState, actions, back) { navigate(SettingsPage.Fonts) }
                    SettingsPage.Alphabet -> AlphabetSettings(uiState, actions, back) { navigate(SettingsPage.Fonts) }
                    SettingsPage.IconPacks -> IconPackSettings(uiState, actions, back) { navigate(SettingsPage.IconDesigner) }
                    SettingsPage.IconDesigner -> {
                        val selectedKey by entry.savedStateHandle.getStateFlow<String?>("icon_designer_app", initialIconDesignerApp?.key).collectAsState()
                        IconDesignerSettings(uiState, actions, selectedKey, back,
                            onChooseApp = { navigate(SettingsPage.IconDesignerApp) },
                            onSelectApp = { key -> entry.savedStateHandle.set("icon_designer_app", key) },
                            fallbackApp = initialIconDesignerApp)
                    }
                    SettingsPage.IconDesignerApp -> AppSelectionSettings(
                        uiState, actions, back, title = stringResource(R.string.icon_designer_choose_app), tag = "icon_designer",
                        selectedKey = navController.previousBackStackEntry?.savedStateHandle?.get<String>("icon_designer_app"),
                        onSelect = { key ->
                            if (isCurrent() && key != null) {
                                navController.previousBackStackEntry?.savedStateHandle?.set("icon_designer_app", key)
                                back()
                            }
                        },
                    )
                    SettingsPage.Calendar -> CalendarSettings(uiState, actions, back,
                        onCalendars = { navigate(SettingsPage.CalendarSources) },
                        onCalendarApp = { navigate(SettingsPage.CalendarApp) },
                        onTasks = { navigate(SettingsPage.TasksOrg) },
                        onAppearance = { navigate(SettingsPage.ClockStyle) })
                    SettingsPage.CalendarSources -> CalendarSourcesSettings(uiState, actions, back)
                    SettingsPage.CalendarApp -> CalendarAppSettings(uiState, actions, back)
                    SettingsPage.TasksOrg -> TasksSettings(uiState, actions, back,
                        onLists = { navigate(SettingsPage.TaskLists) }, onAppearance = { navigate(SettingsPage.ClockStyle) })
                    SettingsPage.TaskLists -> TasksListsSettings(uiState, actions, back)
                    SettingsPage.Advanced -> AdvancedSettings(uiState, actions, back, navigate,
                        onChooseDefaultHome = { if (isCurrent()) actions.requestDefaultHome() })
                    SettingsPage.Language -> LanguageSettings(back)
                    SettingsPage.LabGraceButton -> LabGraceButtonSettings(uiState, actions, back)
                    SettingsPage.LabGesture -> LabGestureSettings(uiState, actions, back)
                    SettingsPage.LabPopup -> LabPopupSettings(uiState, actions, back)
                    SettingsPage.LabAccessibility -> LabAccessibilitySettings(uiState, actions, back)
                    SettingsPage.LabDismiss -> LabDismissSettings(uiState, actions, back)
                    SettingsPage.About -> AboutSettings(back, navigate)
                    SettingsPage.Backup -> BackupSettings(back)
                    SettingsPage.HiddenApps -> HiddenAppsSettings(uiState, actions, back)
                    SettingsPage.PrivateSpace -> ProfileSettingsScreen(uiState, actions, back) { navigate(SettingsPage.PrivateSpaceEditor) }
                    SettingsPage.WorkProfile -> ProfileSettingsScreen(uiState, actions, back, work = true) { navigate(SettingsPage.WorkProfileEditor) }
                    SettingsPage.WorkProfileEditor -> ProfileEditorSettings(uiState, actions, back, work = true, onEditIcon = { app ->
                        if (isCurrent()) {
                            navController.navigate(SettingsPage.IconDesigner.name)
                            navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                        }
                    })
                    SettingsPage.PrivateSpaceEditor -> ProfileEditorSettings(uiState, actions,
                        onBack = { actions.closePrivateSpace(); back() }, onEditIcon = { app ->
                            if (isCurrent()) {
                                navController.navigate(SettingsPage.IconDesigner.name)
                                navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                            }
                        })
                    SettingsPage.Folders -> FolderSettings(uiState, actions, back) { folderId ->
                        if (isCurrent()) navController.navigate("FolderEditor/${Uri.encode(folderId)}")
                    }
                    SettingsPage.AppStacks -> FolderSettings(uiState, actions, back, appStacks = true) { folderId ->
                        if (isCurrent()) navController.navigate("FolderEditor/${Uri.encode(folderId)}")
                    }
                    SettingsPage.FolderEditor -> FolderEditorSettings(entry.arguments?.getString("folderId"), uiState, actions, back,
                        onEditIcon = { app ->
                            if (isCurrent()) {
                                navController.navigate(SettingsPage.IconDesigner.name)
                                navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                            }
                        })
                    SettingsPage.Changelog -> ChangelogSettings(back)
                    SettingsPage.Licenses -> LicenseSettings(back, navigate)
                    SettingsPage.AppLicense -> AppLicenseSettings(back)
                }
                }
            }
        }
      }
     }
    }
}

@Composable
private fun SettingsHome(
    isDefaultHome: Boolean?,
    onSetDefaultHome: () -> Unit,
    onOpenHome: () -> Unit,
    onBack: () -> Unit,
    navigate: (SettingsPage) -> Unit,
) {
    val showDefaultHomeBanner = isDefaultHome == false
    val languageTag by rememberAppLanguage()
    val categories = listOf(
        SettingsPage.Productivity to LauncherSymbol.Star,
        SettingsPage.Themes to LauncherSymbol.Palette,
        SettingsPage.Advanced to LauncherSymbol.Settings,
        SettingsPage.Language to LauncherSymbol.Language,
        SettingsPage.Backup to LauncherSymbol.Encrypted,
        SettingsPage.About to LauncherSymbol.Info,
    )
    SettingsScaffold(stringResource(R.string.settings_title), "settings_root", onBack) { padding ->
        SettingsList(padding) {
            item(key = "settings_intro") { Spacer(Modifier.height(if (showDefaultHomeBanner) 32.dp else 24.dp)) }
            if (showDefaultHomeBanner) {
                item(key = "default_home_banner") { DefaultHomeBanner(onSetDefaultHome) }
                item(key = "default_home_spacing") { Spacer(Modifier.height(32.dp)) }
            }
            categories.forEachIndexed { index, (page, symbol) ->
                item(key = page.name) {
                    val title = when (page) {
                        SettingsPage.Productivity -> R.string.settings_productivity
                        SettingsPage.Themes -> R.string.settings_themes
                        SettingsPage.Advanced -> R.string.settings_advanced
                        SettingsPage.Language -> R.string.settings_language
                        SettingsPage.Backup -> R.string.settings_backup
                        else -> R.string.settings_about
                    }
                    val summary = when (page) {
                        SettingsPage.Productivity -> stringResource(R.string.settings_productivity_summary)
                        SettingsPage.Themes -> stringResource(R.string.settings_themes_summary)
                        SettingsPage.Advanced -> stringResource(R.string.settings_advanced_summary)
                        SettingsPage.Language -> appLanguageSummary(languageTag)
                        SettingsPage.Backup -> stringResource(R.string.settings_backup_summary)
                        else -> stringResource(R.string.settings_about_summary)
                    }
                    SettingsActionItem(
                        stringResource(title), summary, index, categories.size,
                        tag = "settings_category_${page.name.lowercase()}",
                        leading = {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                LauncherIcon(symbol, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        },
                    ) { navigate(page) }
                }
            }
            if (showDefaultHomeBanner) {
                item(key = "direct_home_spacing") { Spacer(Modifier.height(24.dp)) }
                item(key = "direct_home") {
                    SettingsActionItem(stringResource(R.string.settings_open_home), stringResource(R.string.settings_open_home_summary),
                        0, 1, "settings_open_home", leading = { LauncherIcon(LauncherSymbol.Home) }, onClick = onOpenHome)
                }
            }
        }
    }
}

@Composable
private fun DefaultHomeBanner(onClick: () -> Unit) {
    // The default-home action has its own banner above the settings categories.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("settings_default_home"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherIcon(LauncherSymbol.Home, Modifier.size(24.dp))
            Text(stringResource(R.string.set_default_launcher), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            LauncherIcon(LauncherSymbol.Launch, Modifier.size(24.dp))
        }
    }
}

// Same hierarchy motion as Sudoku's MaterialTransitionPatterns: 30 dp travel,
// 300 ms spatial motion, an outgoing 90 ms fade and an incoming 210 ms fade.
// NavHost also seeks these transitions while a predictive-back gesture is held.
internal fun <S> AnimatedContentTransitionScope<S>.settingsEnter(distance: Int, back: Boolean = false) =
    slideIntoContainer(
        towards = if (back) AnimatedContentTransitionScope.SlideDirection.End else AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        initialOffset = { it.coerceIn(-distance, distance) },
    ) + fadeIn(tween(210, delayMillis = 90, easing = LinearEasing))

internal fun <S> AnimatedContentTransitionScope<S>.settingsExit(distance: Int, back: Boolean = false) =
    slideOutOfContainer(
        towards = if (back) AnimatedContentTransitionScope.SlideDirection.End else AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        targetOffset = { it.coerceIn(-distance, distance) },
    ) + fadeOut(tween(90, easing = LinearEasing))
