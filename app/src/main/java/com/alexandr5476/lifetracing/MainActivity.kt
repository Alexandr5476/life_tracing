package com.alexandr5476.lifetracing

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.alexandr5476.lifetracing.daily.DailyRoute
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.serialization.Serializable

class MainActivity : AppCompatActivity() {
    private val appearancePreferences by lazy { AppearancePreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearancePreferences.preferences.collectAsState(initial = AppearancePreferences())
            LifeTracingApp(appearance)
        }
    }
}

/** Navigation identity only: Daily has no domain identifier. */
@Serializable
data object DailyRoot : NavKey

@Composable
@Suppress("FunctionNaming")
fun LifeTracingApp(
    appearance: AppearancePreferences = AppearancePreferences(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
) {
    LifeTracingTheme(
        themeMode = appearance.themeMode,
        accentPaletteId = appearance.accentPaletteId,
        systemIsDark = systemIsDark,
    ) {
        Surface {
            val context = LocalContext.current
            // This is the sole production acquisition point for the lazily-owned controller.
            val controller =
                remember(context.applicationContext) {
                    LifeTracingRuntimeGraph.from(context.applicationContext).dailyController
                }
            val backStack = rememberNavBackStack(DailyRoot)
            NavDisplay(
                backStack = backStack,
                entryProvider =
                    entryProvider {
                        entry<DailyRoot> { DailyRoute(controller) }
                    },
            )
        }
    }
}
