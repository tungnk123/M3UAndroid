package com.m3u.features.main.navgation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.google.accompanist.navigation.animation.composable
import com.m3u.features.main.MainRoute
import com.m3u.ui.model.AppAction

const val mainNavigationRoute = "main_route"

fun NavController.navigateToMain(navOptions: NavOptions? = null) {
    this.navigate(mainNavigationRoute, navOptions)
}

@OptIn(ExperimentalAnimationApi::class)
fun NavGraphBuilder.mainScreen(
    navigateToSubscription: (String, label: String?) -> Unit,
    setAppActions: (List<AppAction>) -> Unit
) {
    composable(mainNavigationRoute) {
        MainRoute(
            navigateToSubscription = navigateToSubscription,
            setAppActions = setAppActions
        )
    }
}
