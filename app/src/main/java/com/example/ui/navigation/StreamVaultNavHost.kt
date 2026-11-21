package com.example.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.downloads.DownloadsScreen
import com.example.ui.screens.browser.BrowserScreen
import com.example.ui.screens.about.AboutScreen
import com.example.ui.screens.history.HistoryScreen
import com.example.ui.screens.audio.AudioLibraryScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.options.DownloadOptionsScreen
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.flow.SharedFlow

private data class TopLevelTab(
    val route: AppRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val topLevelTabs = listOf(
    TopLevelTab(
        route = AppRoute.Home,
        label = "Home",
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_home"
    ),
    TopLevelTab(
        route = AppRoute.Downloads,
        label = "Downloads",
        selectedIcon = Icons.Rounded.Download,
        unselectedIcon = Icons.Outlined.Download,
        testTag = "nav_downloads"
    ),
    TopLevelTab(
        route = AppRoute.History,
        label = "History",
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history"
    ),
    TopLevelTab(
        route = AppRoute.AudioLibrary,
        label = "Audio",
        selectedIcon = Icons.Rounded.MusicNote,
        unselectedIcon = Icons.Outlined.MusicNote,
        testTag = "nav_audio"
    ),
    TopLevelTab(
        route = AppRoute.Settings,
        label = "Settings",
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    ),
    TopLevelTab(
        route = AppRoute.About,
        label = "About",
        selectedIcon = Icons.Rounded.Info,
        unselectedIcon = Icons.Outlined.Info,
        testTag = "nav_about"
    )
)

@Composable
fun StreamVaultNavHost(
    widthSizeClass: WindowWidthSizeClass,
    activeDownloadCount: Int,
    isInPictureInPictureMode: Boolean,
    incomingSharedUrls: SharedFlow<String>,
    initialSharedUrl: String?,
    onConsumeInitialSharedUrl: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val isTopLevel = currentDestination?.let { dest ->
        topLevelTabs.any { tab -> dest.hasRoute(tab.route::class) }
    } ?: true

    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            onConsumeInitialSharedUrl()
            navController.navigate(AppRoute.DownloadOptions(initialSharedUrl)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        incomingSharedUrls.collect { sharedUrl ->
            navController.navigate(AppRoute.DownloadOptions(sharedUrl)) {
                launchSingleTop = true
            }
        }
    }

    fun navigateTopLevel(route: AppRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isTopLevel && !useRail && !isInPictureInPictureMode) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    topLevelTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(tab.route::class)
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTopLevel(tab.route) },
                            icon = {
                                TabIcon(
                                    tab = tab,
                                    selected = selected,
                                    activeDownloadCount = activeDownloadCount
                                )
                            },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isTopLevel && useRail && !isInPictureInPictureMode) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    topLevelTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(tab.route::class)
                        } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(tab.route) },
                            icon = {
                                TabIcon(
                                    tab = tab,
                                    selected = selected,
                                    activeDownloadCount = activeDownloadCount
                                )
                            },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoute.Home,
                    enterTransition = {
                        fadeIn(tween(140)) + slideInHorizontally(
                            animationSpec = tween(160),
                            initialOffsetX = { it / 32 }
                        )
                    },
                    exitTransition = {
                        fadeOut(tween(100)) + slideOutHorizontally(
                            animationSpec = tween(120),
                            targetOffsetX = { -it / 40 }
                        )
                    },
                    popEnterTransition = {
                        fadeIn(tween(140)) + slideInHorizontally(
                            animationSpec = tween(160),
                            initialOffsetX = { -it / 32 }
                        )
                    },
                    popExitTransition = {
                        fadeOut(tween(100)) + slideOutHorizontally(
                            animationSpec = tween(120),
                            targetOffsetX = { it / 40 }
                        )
                    }
                ) {
                    composable<AppRoute.Home> {
                        HomeScreen(
                            onNavigateToOptions = { url ->
                                navController.navigate(AppRoute.DownloadOptions(url))
                            },
                            onNavigateToDownloads = { navigateTopLevel(AppRoute.Downloads) },
                            onNavigateToHistory = { navigateTopLevel(AppRoute.History) },
                            onOpenBrowser = { navController.navigate(AppRoute.Browser) },
                            onPlayVideo = { id ->
                                navController.navigate(AppRoute.Player(id))
                            }
                        )
                    }

                    composable<AppRoute.Downloads> {
                        DownloadsScreen(
                            onGoToHome = { navigateTopLevel(AppRoute.Home) }
                        )
                    }

                    composable<AppRoute.History> {
                        HistoryScreen(
                            onPlayVideo = { id ->
                                navController.navigate(AppRoute.Player(id))
                            },
                            onGoToHome = { navigateTopLevel(AppRoute.Home) }
                        )
                    }

                    composable<AppRoute.AudioLibrary> {
                        AudioLibraryScreen()
                    }

                    composable<AppRoute.Settings> {
                        SettingsScreen()
                    }

                    composable<AppRoute.About> {
                        AboutScreen()
                    }

                    composable<AppRoute.Browser> {
                        BrowserScreen(
                            onBack = { navController.popBackStack() },
                            onSendToDownloader = { url ->
                                navController.navigate(AppRoute.DownloadOptions(url))
                            }
                        )
                    }

                    composable<AppRoute.DownloadOptions> {
                        DownloadOptionsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToDownloads = {
                                navController.popBackStack()
                                navigateTopLevel(AppRoute.Downloads)
                            },
                            onPlayVideo = { id ->
                                navController.navigate(AppRoute.Player(id))
                            }
                        )
                    }

                    composable<AppRoute.Player> {
                        PlayerScreen(
                            isInPictureInPictureMode = isInPictureInPictureMode,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: TopLevelTab,
    selected: Boolean,
    activeDownloadCount: Int
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (tab.route == AppRoute.Downloads && activeDownloadCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(activeDownloadCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = tab.label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.label)
    }
}
