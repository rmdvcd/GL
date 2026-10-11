package dev.gl.license.presentation

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.gl.license.R
import dev.gl.license.presentation.contact.ContactScreen
import dev.gl.license.presentation.generator.GeneratorScreen
import dev.gl.license.presentation.navigation.Route
import dev.gl.license.presentation.registry.DetailScreen
import dev.gl.license.presentation.registry.RegistryScreen
import dev.gl.license.presentation.theme.GlMotion
import dev.gl.license.presentation.trust.TrustScreen
import dev.gl.license.presentation.theme.GlTheme
import dev.gl.license.security.SecureUi

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureUi.enableFlagSecure(this)
        enableEdgeToEdge()
        setContent {
            GlTheme {
                val nav = rememberNavController()
                val back by nav.currentBackStackEntryAsState()
                val route = back?.destination?.route
                val showBar = route == Route.Generator || route == Route.Contact ||
                    route == Route.Registry || route == Route.Trust
                val generatorLabel = stringResource(R.string.tab_generator)
                val contactLabel = stringResource(R.string.tab_contact)
                val registryLabel = stringResource(R.string.tab_registry)
                val trustLabel = stringResource(R.string.tab_trust)
                val navigateToRoot: (String) -> Unit = { destination ->
                    nav.navigate(destination) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(nav.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                }
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (showBar) {
                            NavigationBar(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                                NavigationBarItem(
                                    selected = route == Route.Generator,
                                    onClick = { navigateToRoot(Route.Generator) },
                                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    label = { Text(generatorLabel) },
                                    alwaysShowLabel = true,
                                    modifier = Modifier.semantics { contentDescription = generatorLabel },
                                )
                                NavigationBarItem(
                                    selected = route == Route.Contact,
                                    onClick = { navigateToRoot(Route.Contact) },
                                    icon = { Icon(Icons.Default.ContactPage, contentDescription = null) },
                                    label = { Text(contactLabel) },
                                    alwaysShowLabel = true,
                                    modifier = Modifier.semantics { contentDescription = contactLabel },
                                )
                                NavigationBarItem(
                                    selected = route == Route.Registry,
                                    onClick = { navigateToRoot(Route.Registry) },
                                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                                    label = { Text(registryLabel) },
                                    alwaysShowLabel = true,
                                    modifier = Modifier.semantics { contentDescription = registryLabel },
                                )
                                NavigationBarItem(
                                    selected = route == Route.Trust,
                                    onClick = { navigateToRoot(Route.Trust) },
                                    icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                                    label = { Text(trustLabel) },
                                    alwaysShowLabel = true,
                                    modifier = Modifier.semantics { contentDescription = trustLabel },
                                )
                            }
                        }
                    }
                ) { pad ->
                    NavHost(
                        navController = nav,
                        startDestination = Route.Generator,
                        modifier = Modifier.padding(pad),
                        enterTransition = { fadeIn(tween(GlMotion.normal)) },
                        exitTransition = { fadeOut(tween(GlMotion.fast)) },
                    ) {
                        composable(Route.Generator) {
                            GeneratorScreen(screenVisible = route == Route.Generator)
                        }
                        composable(Route.Contact) {
                            ContactScreen()
                        }
                        composable(Route.Registry) {
                            RegistryScreen(
                                screenVisible = route == Route.Registry,
                                onOpenDetail = { id -> nav.navigate(Route.detail(id)) }
                            )
                        }
                        composable(Route.Trust) {
                            TrustScreen()
                        }
                        composable(
                            Route.Detail,
                            arguments = listOf(navArgument("id") { type = NavType.StringType })
                        ) {
                            DetailScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
