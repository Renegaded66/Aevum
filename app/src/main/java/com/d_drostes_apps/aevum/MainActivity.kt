package com.d_drostes_apps.aevum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.navigation.AppDestination
import com.d_drostes_apps.aevum.navigation.AppNavHost
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AevumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AevumMainApp()
                }
            }
        }
    }

    // M18.22/M18.24: Zuverlaessiger Notification-Restore beim App-Oeffnen.
    // AevumApplication.onCreate laeuft nur beim Cold-Start. onResume laeuft
    // IMMER — auch wenn die App nur im Hintergrund war (Energie-Sparmodus,
    // Swipe-away, Update). Prueft ob eine Live-Session laeuft und startet
    // den Notification-Service falls noetig.
    //
    // M18.24: Zusaetzlich wird geprueft, ob die Notification mit ID 9001
    // wirklich in der Benachrichtigungszeile aktiv ist. Falls der Service
    // gekillt wurde (Battery-Optimierung, OEM-RAM-Manager), aber die Session
    // noch laeuft, wird der Service neu gestartet — die Notification
    // "ploppt" dann wieder auf.
    override fun onResume() {
        super.onResume()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val deps = EntryPointAccessors.fromApplication(application, Deps::class.java)
                val manager = deps.liveActivityManager()
                // M18.24: liveSession ist jetzt SharingStarted.Eagerly —
                // .value liefert IMMER den echten DB-Wert, auch ohne
                // aktiven Subscriber. Kein first() mehr noetig.
                val session = manager.liveSession.value
                if (session != null && session.isLive) {
                    // M18.24: Pruefen ob die Notification wirklich aktiv ist.
                    // activeNotifications kann auf manchen OEM-Geraeten
                    // (Xiaomi/Samsung) eine SecurityException werfen —
                    // defensiv abfangen, der Service-Start ist wichtiger.
                    var notificationActive = false
                    try {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        notificationActive = nm.activeNotifications.any { it.id == com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.NOTIFICATION_ID }
                    } catch (_: Exception) { }
                    if (!notificationActive) {
                        com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
                    }
                }
            } catch (_: Exception) { }
        }
    }
}

@Composable
private fun AevumMainApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val bottomTabs = listOf(
        MainTab(AppDestination.Dashboard, "Heute"),
        MainTab(AppDestination.Insights, "Insights"),
        // M18.37: Kalender und Timeline vertauscht (User-Wunsch):
        // vorher [Kalender, Timeline], jetzt [Timeline, Kalender].
        MainTab(AppDestination.Timeline, "Timeline"),
        // M18.61: Digital Balance ersetzt den Kalender-Tab
        MainTab(AppDestination.DigitalBalance, "Balance"),
        MainTab(AppDestination.Settings, "Settings")
    )
    val showBottomBar = currentDestination?.route?.let { route ->
        bottomTabs.any { tab -> route.matchesTopLevel(tab.destination.route) }
    } ?: true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AevumBottomNavigation(
                    tabs = bottomTabs,
                    currentDestination = currentDestination,
                    onTabSelected = { tab ->
                        navController.navigate(tab.destination.route) {
                            popUpTo(AppDestination.Dashboard.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding: PaddingValues ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun AevumBottomNavigation(
    tabs: List<MainTab>,
    currentDestination: NavDestination?,
    onTabSelected: (MainTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 12.dp
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp
        ) {
            tabs.forEach { tab ->
                val selected = currentDestination.isTopLevelRoute(tab.destination.route)
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label
                        )
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private data class MainTab(
    val destination: AppDestination,
    val label: String,
    val icon: ImageVector = when (destination) {
        AppDestination.Dashboard -> Icons.Outlined.SpaceDashboard
        AppDestination.Insights -> Icons.Outlined.Insights
        AppDestination.Calendar -> Icons.Outlined.CalendarMonth
        AppDestination.DigitalBalance -> Icons.Outlined.Insights
        AppDestination.Timeline -> Icons.Outlined.Timeline
        AppDestination.Settings -> Icons.Outlined.Settings
        else -> Icons.Outlined.SpaceDashboard
    },
    val selectedIcon: ImageVector = when (destination) {
        AppDestination.Dashboard -> Icons.Filled.SpaceDashboard
        AppDestination.Insights -> Icons.Filled.Insights
        AppDestination.Calendar -> Icons.Filled.CalendarMonth
        AppDestination.DigitalBalance -> Icons.Filled.Insights
        AppDestination.Timeline -> Icons.Filled.Timeline
        AppDestination.Settings -> Icons.Filled.Settings
        else -> Icons.Filled.SpaceDashboard
    }
)

private fun NavDestination?.isTopLevelRoute(route: String): Boolean = this?.hierarchy?.any { destination ->
    destination.route?.matchesTopLevel(route) == true
} == true

private fun String.matchesTopLevel(route: String): Boolean = this == route || this.startsWith("$route/")