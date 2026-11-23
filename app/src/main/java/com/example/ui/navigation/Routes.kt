package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Downloads : AppRoute

    @Serializable
    data object History : AppRoute

    @Serializable
    data object AudioLibrary : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data class Browser(val initialUrl: String? = null) : AppRoute

    @Serializable
    data class DownloadOptions(val url: String) : AppRoute

    @Serializable
    data class Player(val downloadId: String) : AppRoute
}
