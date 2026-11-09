package com.galaxyrio.gracelauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherSettingsRepository
import com.galaxyrio.gracelauncher.data.LauncherItemsRepository
import com.galaxyrio.gracelauncher.data.LauncherProfiles
import com.galaxyrio.gracelauncher.data.LauncherProfileType
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.platform.HomeWidgetHost
import com.galaxyrio.gracelauncher.platform.hasOptionalConfiguration
import com.galaxyrio.gracelauncher.platform.initialWidgetOptions
import com.galaxyrio.gracelauncher.platform.widgetConfigurationOptions
import com.galaxyrio.gracelauncher.platform.WidgetReconfigurationRoute
import com.galaxyrio.gracelauncher.platform.widgetReconfigurationRoute
import com.galaxyrio.gracelauncher.ui.LauncherAppTheme
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import com.galaxyrio.gracelauncher.ui.widgets.WidgetPicker
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns Android's binding/configuration round trips, including activity recreation. */
class WidgetSetupActivity : AppCompatActivity() {
    private val viewModel: LauncherViewModel by viewModels()
    private val repository by lazy { LauncherSettingsRepository(LauncherDatabase.getInstance(this)) }
    private val itemsRepository by lazy { LauncherItemsRepository(LauncherDatabase.getInstance(this)) }
    private val popupOwner get() = intent.getStringExtra(EXTRA_POPUP_OWNER)
    private val manager by lazy { AppWidgetManager.getInstance(this) }
    private val host by lazy { HomeWidgetHost(this) }
    private var pendingId = -1
    private var expectedProvider: String? = null
    private var configuringExisting = false
    private var reconfigurationReady = false
    private var gridColumns = 4
    private var homeSidePadding = 44
    private var stage = "loading"
    private var expectedProfile: Long? = null
    private var saving by mutableStateOf(false)
    private var busy by mutableStateOf(true)
    private var providers by mutableStateOf<List<AppWidgetProviderInfo>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingId = savedInstanceState?.getInt("pendingId", -1) ?: -1
        if (pendingId >= 0) host.trackPendingId(pendingId)
        expectedProvider = savedInstanceState?.getString("expectedProvider")
        configuringExisting = savedInstanceState?.getBoolean("configuringExisting") ?: false
        stage = savedInstanceState?.getString("stage") ?: "loading"
        expectedProfile = savedInstanceState?.getLong("expectedProfile", -1)?.takeIf { it >= 0 }
        setContent {
            LauncherAppTheme(viewModel) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BackHandler(enabled = saving) { }
                CompositionLocalProvider(LocalLauncherAppearance provides rememberLauncherAppearance(state.textMode,
                    themedIcons = state.themedIcons, iconSize = state.settings.iconDesign?.design?.iconSize ?: 100)) {
                    WidgetPicker(providers, busy, state, onBack = { if (!saving) cancelSetup() }, onSelect = ::select)
                }
            }
        }
        lifecycleScope.launch {
            try {
                val settings = repository.snapshots.first().settings
                gridColumns = settings.widgetScreen.columns
                homeSidePadding = settings.listAppearance.sidePadding
                val current = settings.homeLayout
                val popupWidgets = itemsRepository.snapshots.first().popups.values.flatten().mapNotNull { it.widget?.widgetId }.toSet()
                // Only discard this host's abandoned setup IDs; never touch the saved widget.
                host.appWidgetIds.filter { it != current.widgetId && it != pendingId && it !in popupWidgets && !host.isPendingId(it) }.forEach(host::deleteAppWidgetId)
                if (pendingId >= 0) {
                    if ((current.widgetId == pendingId || pendingId in popupWidgets) && !configuringExisting) { host.untrackPendingId(pendingId); pendingId = -1; finish() }
                    else if (savedInstanceState?.getBoolean("saving", false) == true) finishAdding()
                    else if (configuringExisting && stage == "configuration_queued") configure()
                    // Already launched configurations wait for their restored activity result.
                    return@launch
                }
                val configureId = intent.getIntExtra(EXTRA_CONFIGURE_ID, -1)
                if (configureId >= 0) {
                    if (configureId != current.widgetId && configureId !in popupWidgets) { fail(R.string.widget_unavailable); return@launch }
                    pendingId = configureId
                    configuringExisting = true
                    configure()
                } else {
                    providers = withContext(Dispatchers.IO) {
                        LauncherProfiles(this@WidgetSetupActivity).snapshot().profiles.filter { profile ->
                            profile.isPublic &&
                                (profile.type != LauncherProfileType.Work ||
                                    (profile.quietMode != true && profile.unlocked != false))
                        }.flatMap { profile ->
                            // OEM clone profiles may not support widgets. Keep the other profiles
                            // available even when optional profile state queries are unsupported.
                            try { manager.getInstalledProvidersForProfile(profile.user) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                Log.w(TAG, "Unable to load widget providers for ${profile.user}", error)
                                emptyList()
                            }
                        }.distinctBy { it.provider.flattenToString() to it.profile }
                    }
                    busy = false
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(R.string.widget_setup_error, error)
            }
        }
    }

    private fun select(info: AppWidgetProviderInfo) {
        if (busy) return
        busy = true
        stage = "binding"
        try {
            pendingId = host.allocateAppWidgetId().also(host::trackPendingId)
            expectedProvider = info.provider.flattenToString()
            expectedProfile = LauncherProfiles.serialNumber(this, info.profile)
                ?: error("Unknown widget profile")
            val options = initialWidgetOptions(this, info, popupOwner, gridColumns, homeSidePadding)
            Log.i(TAG, "bind id=$pendingId provider=$expectedProvider profile=$expectedProfile " +
                "min=${info.minWidth}x${info.minHeight} resizeMode=${info.resizeMode} configure=${info.configure} " +
                "size=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, options)) configure()
            else startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            }, REQUEST_BIND)
        } catch (error: Exception) { fail(R.string.widget_bind_error, error) }
    }

    private fun configure() {
        stage = "configuration"
        val info = runCatching { manager.getAppWidgetInfo(pendingId) }
            .getOrElse { fail(R.string.widget_unavailable, it); return }
        if (info == null || (expectedProvider != null && info.provider.flattenToString() != expectedProvider) ||
            (expectedProfile != null && expectedProfile != LauncherProfiles.serialNumber(this, info.profile))) {
            fail(R.string.widget_unavailable); return
        }
        expectedProvider = info.provider.flattenToString()
        // Optional initial configuration can be deferred until the user opens Settings.
        // Explicit reconfiguration must still open the provider's page.
        if (info.configure == null || (!configuringExisting && info.hasOptionalConfiguration())) {
            if (configuringExisting) fail(R.string.widget_no_configuration) else finishAdding()
            return
        }
        if (configuringExisting) {
            // The Settings trampoline may still be in onCreate, without a visible window.
            // Persist the queued stage so recreation before launch can resume it exactly once.
            stage = "configuration_queued"
            reconfigurationReady = true
            launchReconfigurationWhenReady()
            return
        }
        try {
            // This API also handles providers in a managed/work profile.
            Log.i(TAG, "configure id=$pendingId provider=${info.provider} existing=$configuringExisting")
            host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQUEST_CONFIGURE, widgetConfigurationOptions())
        } catch (error: Exception) { fail(R.string.widget_configuration_launch_error, error) }
    }

    override fun onPostResume() {
        super.onPostResume()
        launchReconfigurationWhenReady()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) launchReconfigurationWhenReady()
    }

    private fun launchReconfigurationWhenReady() {
        if (!reconfigurationReady || !configuringExisting || pendingId < 0 || isFinishing || isDestroyed) return
        try {
            val info = manager.getAppWidgetInfo(pendingId)
            if (info == null || info.provider.flattenToString() != expectedProvider) {
                fail(R.string.widget_unavailable)
                return
            }
            val configuration = info.configure ?: return fail(R.string.widget_no_configuration)
            val route = widgetReconfigurationRoute(
                queued = stage == "configuration_queued",
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                focused = window.decorView.hasWindowFocus(),
                sameProfile = info.profile == Process.myUserHandle(),
            )
            if (route == WidgetReconfigurationRoute.Wait) return
            // Consume before starting: focus/resume callbacks must not reopen a page after Back.
            reconfigurationReady = false
            stage = "configuration"
            if (route == WidgetReconfigurationRoute.Direct) {
                // Standard explicit configuration preserves the foreground Activity caller and
                // avoids the system-created IntentSender for same-profile reconfiguration.
                // No NEW_TASK flag: keep the result attached to this existing widget ID.
                val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(configuration)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                try {
                    Log.i(TAG, "reconfigure route=direct id=$pendingId provider=${info.provider}")
                    startActivityForResult(configureIntent, REQUEST_CONFIGURE)
                    return
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "direct configure unavailable id=$pendingId; trying host", error)
                } catch (error: SecurityException) {
                    Log.w(TAG, "direct configure denied id=$pendingId; trying host", error)
                }
            }
            // Keep the host's permission-checked path for cross-profile/non-exported providers.
            // A cancelled result is never retried: Back must not reopen the configuration page.
            Log.i(TAG, "reconfigure route=host id=$pendingId provider=${info.provider}")
            host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQUEST_CONFIGURE, widgetConfigurationOptions())
        } catch (error: Exception) {
            fail(R.string.widget_configuration_launch_error, error)
        }
    }

    @Deprecated("Platform AppWidgetHost configuration uses request codes")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return
        Log.i(TAG, "result stage=$stage id=$pendingId request=$requestCode result=$resultCode")
        if (resultCode != Activity.RESULT_OK) { cancelSetup(); return }
        if (pendingId < 0) { fail(R.string.widget_setup_error); return }
        if (requestCode == REQUEST_BIND) configure()
        else if (configuringExisting) { host.untrackPendingId(pendingId); pendingId = -1; finish() }
        else finishAdding()
    }

    private fun finishAdding() {
        if (saving) return
        val info = runCatching { manager.getAppWidgetInfo(pendingId) }
            .getOrElse { fail(R.string.widget_unavailable, it); return } ?: return fail(R.string.widget_unavailable)
        val id = pendingId
        saving = true
        stage = "saving"
        lifecycleScope.launch {
            try {
                val layout = HomeLayout(widgetId = id, widgetProvider = info.provider.flattenToString(),
                    widgetLabel = runCatching { info.loadLabel(packageManager) }.getOrDefault(info.provider.className))
                val owner = popupOwner ?: com.galaxyrio.gracelauncher.data.HomeContentKey
                itemsRepository.updatePopup(owner, emptyList()) { entries ->
                    if (entries.any { it.widget?.widgetId == id }) entries else entries + PopupItem("widget:$id", layout)
                }
                host.untrackPendingId(pendingId)
                Log.i(TAG, "saved id=$id provider=${info.provider}")
                pendingId = -1 // The database owns it now; cancellation must not delete it.
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(R.string.settings_storage_save_error, error)
            } finally { saving = false }
        }
    }

    private fun fail(message: Int, error: Throwable? = null) {
        Log.e(TAG, "failed stage=$stage id=$pendingId provider=$expectedProvider existing=$configuringExisting", error)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        cancelSetup()
    }

    private fun cancelSetup() {
        reconfigurationReady = false
        Log.i(TAG, "cancel stage=$stage id=$pendingId existing=$configuringExisting")
        if (!configuringExisting && pendingId >= 0) runCatching { host.deleteAppWidgetId(pendingId) }
        host.untrackPendingId(pendingId)
        pendingId = -1
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pendingId", pendingId)
        outState.putString("expectedProvider", expectedProvider)
        outState.putBoolean("configuringExisting", configuringExisting)
        outState.putBoolean("saving", saving)
        outState.putString("stage", stage)
        expectedProfile?.let { outState.putLong("expectedProfile", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !saving) host.untrackPendingId(pendingId)
        if (isFinishing && !configuringExisting && !saving && pendingId >= 0) runCatching { host.deleteAppWidgetId(pendingId) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GraceWidgetSetup"
        const val EXTRA_POPUP_OWNER = "popupOwner"
        const val EXTRA_CONFIGURE_ID = "configureWidgetId"
        private const val REQUEST_BIND = 4001
        private const val REQUEST_CONFIGURE = 4002
    }
}
