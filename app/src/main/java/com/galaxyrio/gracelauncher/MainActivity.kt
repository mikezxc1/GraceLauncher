package com.galaxyrio.gracelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.galaxyrio.gracelauncher.platform.LauncherAppTransitions
import com.galaxyrio.gracelauncher.ui.LauncherAppTheme
import com.galaxyrio.gracelauncher.ui.LauncherRoute
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import com.galaxyrio.gracelauncher.ui.components.LocalAppTransitions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val launcherViewModel: LauncherViewModel by viewModels()
    private var widgetEditRequest by mutableIntStateOf(0)
    private val appTransitions by lazy { LauncherAppTransitions(this, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EDIT_WIDGET, false)) { widgetEditRequest++; intent.removeExtra(EXTRA_EDIT_WIDGET) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launcherViewModel.uiState
                    .filter { !it.isLoadingSettings && !it.settingsLoadFailed }
                    .map { it.settings.allowScreenRotation }
                    .distinctUntilChanged()
                    .collect { allowRotation ->
                        val orientation = if (allowRotation) ActivityInfo.SCREEN_ORIENTATION_USER
                            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        if (requestedOrientation != orientation) requestedOrientation = orientation
                    }
            }
        }
        setContent {
            CompositionLocalProvider(LocalAppTransitions provides appTransitions) {
                LauncherAppTheme(launcherViewModel) {
                    LauncherRoute(viewModel = launcherViewModel, widgetEditRequest = widgetEditRequest)
                }
            }
        }
        appTransitions.onHomeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        launcherViewModel.refreshOnResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EDIT_WIDGET, false)) { widgetEditRequest++; intent.removeExtra(EXTRA_EDIT_WIDGET) }
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            launcherViewModel.requestReturnHome()
            appTransitions.onHomeIntent(intent)
        }
    }

    override fun onPause() {
        appTransitions.close()
        super.onPause()
    }

    override fun onDestroy() {
        appTransitions.close()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) appTransitions.close()
        return super.dispatchTouchEvent(event)
    }

    companion object { const val EXTRA_EDIT_WIDGET = "editHomeWidget" }
}
