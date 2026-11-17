package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object SplashRoute

@Serializable
data object HomeRoute

@Serializable
data object CategoriesRoute

@Serializable
data object FavoritesRoute

@Serializable
data object SettingsRoute

@Serializable
data class DetailRoute(
    val startIndex: Int,
    val feedSource: String = FEED_MAIN,
) {
    companion object {
        const val FEED_MAIN = "MAIN"
        const val FEED_FAVORITES = "FAVORITES"
    }
}
