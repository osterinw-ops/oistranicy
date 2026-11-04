package ru.moistranicy.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.ShelfRepository

val NoInsets = WindowInsets(0, 0, 0, 0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        ShelfRepository.init(applicationContext)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("shelf", "Полка", Icons.Filled.Home),
    Tab("search", "Поиск", Icons.Filled.Search),
    Tab("profile", "Профиль", Icons.Filled.Person),
)

fun NavHostController.openBook(id: String) = navigate("book/$id")
fun NavHostController.openFeed(title: String, href: String) =
    navigate("feed?title=${Uri.encode(title)}&href=${Uri.encode(href)}")

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        contentWindowInsets = NoInsets,
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "shelf", modifier = Modifier.padding(pad)) {
            composable("shelf") { ShelfScreen(onBook = { nav.openBook(it) }) }
            composable("search") {
                SearchScreen(onBook = { nav.openBook(it) }, onFeed = { t, h -> nav.openFeed(t, h) })
            }
            composable("profile") { ProfileScreen(onBook = { nav.openBook(it) }) }
            composable("book/{id}") { e ->
                BookScreen(
                    id = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onFeed = { t, h -> nav.openFeed(t, h) },
                )
            }
            composable(
                "feed?title={title}&href={href}",
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("href") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                FeedScreen(
                    title = e.arguments?.getString("title").orEmpty(),
                    href = e.arguments?.getString("href").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onBook = { nav.openBook(it) },
                    onFeed = { t, h -> nav.openFeed(t, h) },
                )
            }
        }
    }
}
