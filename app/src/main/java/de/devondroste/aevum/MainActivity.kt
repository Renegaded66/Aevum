package de.devondroste.aevum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import de.devondroste.aevum.navigation.AppDestination
import de.devondroste.aevum.navigation.AppNavHost
import de.devondroste.aevum.ui.theme.AevumTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun liveActivityManager(): de.devondroste.aevum.domain.liveactivity.LiveActivityManager
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

    // M18.22: Zuverlaessiger Notification-Restore beim App-Oeffnen.
    // AevumApplication.onCreate laeuft nur beim Cold-Start. onResume laeuft
    // IMMER — auch wenn die App nur im Hintergrund war (Energie-Sparmodus,
    // Swipe-away, Update). Prueft ob eine Live-Session laeuft und startet
    // den Notification-Service falls noetig.
    override fun onResume() {
        super.onResume()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val deps = EntryPointAccessors.fromApplication(application, Deps::class.java)
                val manager = deps.liveActivityManager()
                // first() statt .value — WhileSubscribed liefert ohne
                // aktiven Subscriber immer null (bekannter M18.21-Fix).
                val session = manager.liveSession.first()
                if (session != null && session.isLive) {
                    de.devondroste.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
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
        MainTab(AppDestination.Timeline, "Timeline"),
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentDestination.isTopLevelRoute(tab.destination.route),
                onClick = { onTabSelected(tab) },
                icon = { Text(tab.icon) },
                label = { Text(tab.label) }
            )
        }
    }
}

private data class MainTab(
    val destination: AppDestination,
    val label: String,
    val icon: String = when (destination) {
        AppDestination.Dashboard -> "◷"
        AppDestination.Insights -> "◌"
        AppDestination.Timeline -> "▤"
        AppDestination.Settings -> "⚙"
        else -> "•"
    }
)

private fun NavDestination?.isTopLevelRoute(route: String): Boolean = this?.hierarchy?.any { destination ->
    destination.route?.matchesTopLevel(route) == true
} == true

private fun String.matchesTopLevel(route: String): Boolean = this == route || this.startsWith("$route/")