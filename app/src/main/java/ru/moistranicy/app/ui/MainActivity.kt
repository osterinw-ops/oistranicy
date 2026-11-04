@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import ru.moistranicy.app.data.CycleCheckWorker
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.Notifier
import ru.moistranicy.app.data.ReadStatus
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.ShelfRepository

val NoInsets = WindowInsets(0, 0, 0, 0)

class MainActivity : ComponentActivity() {
    /** Вкладка, которую нужно открыть (например, из уведомления). */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        ShelfRepository.init(applicationContext)
        CycleRepository.init(applicationContext)
        Notifier.ensureChannel(applicationContext)
        CycleCheckWorker.schedule(applicationContext)
        openRequest.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppRoot(openRequest.value) { openRequest.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(Notifier.EXTRA_OPEN)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("shelf", "Полка", Icons.Filled.Home),
    Tab("search", "Поиск", Icons.Filled.Search),
    Tab("profile", "Профиль", Icons.Filled.Person),
)

fun NavHostController.openBook(id: String) = navigate("book/$id")
fun NavHostController.openAuthor(id: String) = navigate("author/$id")

@Composable
fun AppRoot(openRequest: String?, onOpenHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val cycles by CycleRepository.cycles.collectAsState()
    val newCount = cycles.values.sumOf { it.newIds.size }
    val context = LocalContext.current

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    LaunchedEffect(openRequest) {
        if (openRequest == "cycles") {
            ShelfUi.tab = ReadStatus.WANT.ordinal
            goTab("shelf")
        }
        if (openRequest != null) onOpenHandled()
    }

    // Разрешение на уведомления спрашиваем, когда появился первый отслеживаемый цикл
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val hasCycles = cycles.isNotEmpty()
    LaunchedEffect(hasCycles) {
        if (hasCycles && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { goTab(t.route) },
                        icon = {
                            if (t.route == "shelf" && newCount > 0) {
                                BadgedBox(badge = { Badge { Text("$newCount") } }) { Icon(t.icon, contentDescription = null) }
                            } else {
                                Icon(t.icon, contentDescription = null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "shelf", modifier = Modifier.padding(pad)) {
            composable("shelf") { ShelfScreen(onBook = { nav.openBook(it) }) }
            composable("search") {
                SearchScreen(onBook = { nav.openBook(it) }, onAuthor = { nav.openAuthor(it) })
            }
            composable("profile") { ProfileScreen(onBook = { nav.openBook(it) }) }
            composable("book/{id}") { e ->
                BookScreen(
                    id = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onBook = { nav.openBook(it) },
                    onAuthor = { nav.openAuthor(it) },
                )
            }
            composable("author/{id}") { e ->
                AuthorScreen(
                    id = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onBook = { nav.openBook(it) },
                )
            }
        }
    }
}
