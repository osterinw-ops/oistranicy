@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.Manifest
import android.content.Intent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import ru.moistranicy.app.data.AuthorStats
import ru.moistranicy.app.data.KnownAuthors
import ru.moistranicy.app.data.RecBlock
import ru.moistranicy.app.data.Account
import ru.moistranicy.app.data.SyncManager
import ru.moistranicy.app.data.SocialSync
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.wrapContentWidth
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

/** Счётчики «сбросов» вкладок: экраны прокручиваются к началу, когда значение меняется. */
object TabReset {
    val nonces = mutableStateMapOf<String, Int>()
    fun bump(route: String) { nonces[route] = (nonces[route] ?: 0) + 1 }
}

class MainActivity : ComponentActivity() {
    /** Вкладка, которую нужно открыть (например, из уведомления). */
    private val openRequest = mutableStateOf<String?>(null)
    /** Файл книги, открытый через «Открыть с помощью» — импортируем в читалку. */
    private val importUri = mutableStateOf<android.net.Uri?>(null)

    private fun takeBook(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null) importUri.value = i.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        ShelfRepository.init(applicationContext)
        CycleRepository.init(applicationContext)
        ru.moistranicy.app.data.LangCache.init(applicationContext)
        ru.moistranicy.app.data.reader.Library.init(applicationContext)
        ru.moistranicy.app.data.reader.ReaderPrefs.init(applicationContext)
        AuthorStats.init(applicationContext)
        KnownAuthors.init(applicationContext)
        RecBlock.init(applicationContext)
        Account.init(applicationContext)
        SocialSync.init(applicationContext)
        SyncManager.syncInBackground()
        CycleRepository.refreshOutdatedInBackground()
        CycleRepository.checkLanguagesInBackground()
        ShelfRepository.repairSeriesInBackground()
        SocialSync.pushInBackground()
        Notifier.ensureChannel(applicationContext)
        CycleCheckWorker.schedule(applicationContext)
        openRequest.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)
        takeBook(intent)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val dark = isAppDark()
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                    onDispose {}
                }
                AppRoot(openRequest.value, { openRequest.value = null }, importUri.value) { importUri.value = null }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        KnownAuthors.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(Notifier.EXTRA_OPEN)
        takeBook(intent)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("shelf", "Полка", Icons.Filled.Home),
    Tab("recs", "Рекомендации", Icons.Filled.ThumbUp),
    Tab("search", "Поиск", Icons.Filled.Search),
    Tab("profile", "Профиль", Icons.Filled.Person),
)

fun NavHostController.openBook(id: String) = navigate("book/$id")
fun NavHostController.openAuthor(id: String) = navigate("author/$id")
fun NavHostController.openUser(id: String) = navigate("user/$id")

@Composable
fun AppRoot(
    openRequest: String?,
    onOpenHandled: () -> Unit,
    importUri: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // вкладка, на которой пользователь сейчас (в том числе на вложенных экранах)
    var currentTab by remember { mutableStateOf("shelf") }
    LaunchedEffect(route) {
        val r = route
        if (r != null && tabs.any { it.route == r }) currentTab = r
    }
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
            currentTab = "shelf"
        }
        // служебные переходы (используются при съёмке скриншотов): tab:<вкладка>, shelf:<статус>, book:<id>, author:<id>
        when {
            openRequest == null -> {}
            openRequest.startsWith("tab:") -> openRequest.removePrefix("tab:").let { goTab(it); currentTab = it }
            openRequest.startsWith("shelf:") -> {
                ReadStatus.entries.firstOrNull { it.name == openRequest.removePrefix("shelf:") }?.let { ShelfUi.tab = it.ordinal }
                goTab("shelf"); currentTab = "shelf"
            }
            openRequest.startsWith("book:") -> nav.openBook(openRequest.removePrefix("book:"))
            openRequest.startsWith("author:") -> nav.openAuthor(openRequest.removePrefix("author:"))
        }
        if (openRequest != null) onOpenHandled()
    }

    // книга, открытая через «Открыть с помощью», — в библиотеку и сразу в читалку
    var importError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(importUri) {
        val u = importUri ?: return@LaunchedEffect
        onImportHandled()
        try {
            val b = ru.moistranicy.app.data.reader.BookImporter.import(context, u)
            nav.navigate("reader/${b.id}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            importError = e.message ?: "Не удалось открыть книгу"
        }
    }
    importError?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Книга не открылась") },
            text = { Text(msg) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { importError = null }) { Text("Понятно") } },
        )
    }
    /** Кнопка-логотип: последняя книга на месте остановки, без книг — библиотека. */
    fun openReader() {
        val last = ru.moistranicy.app.data.reader.Library.lastOpened()
            ?: ru.moistranicy.app.data.reader.Library.books.value.values.maxByOrNull { it.addedAt }
        nav.navigate(if (last != null) "reader/${last.id}" else "library") { launchSingleTop = true }
    }
    val inReader = route?.startsWith("reader/") == true

    // Разрешение на уведомления спрашиваем, когда появился первый отслеживаемый цикл
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val hasCycles = cycles.isNotEmpty()
    LaunchedEffect(hasCycles) {
        if (hasCycles && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    CompositionLocalProvider(LocalOpenProfile provides null) { // соцфункции пока отключены
    Scaffold(
        contentWindowInsets = NoInsets,
        bottomBar = {
            if (!inReader) NavigationBar {
                tabs.forEachIndexed { ti, t ->
                    if (ti == 2) {
                        // по центру — логотип: открывает читалку там, где остановились
                        NavigationBarItem(
                            selected = false,
                            onClick = { openReader() },
                            icon = { ReaderLogoButton() },
                            label = null,
                            alwaysShowLabel = false,
                        )
                    }
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            if (currentTab == t.route) {
                                // повторное нажатие: сбросить вкладку к начальной странице
                                when (t.route) {
                                    "search" -> resetSearch()
                                    "shelf" -> ShelfUi.reset()
                                    "recs" -> resetRecsFilters()
                                }
                                TabReset.bump(t.route)
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            } else {
                                goTab(t.route)
                            }
                            currentTab = t.route
                        },
                        icon = {
                            if (t.route == "shelf" && newCount > 0) {
                                BadgedBox(badge = { Badge { Text("$newCount") } }) { Icon(t.icon, contentDescription = null) }
                            } else {
                                Icon(t.icon, contentDescription = null)
                            }
                        },
                        label = {
                            // длинное «Рекомендации» не обрезаем: подпись может чуть выходить за свою ячейку
                            Text(
                                t.label, fontSize = 10.sp, letterSpacing = (-0.2).sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                        },
                    )
                }
            }
        },
    ) { pad ->
        // свайп влево / вправо на главных разделах переключает соседний раздел (Полка ↔ Авторы ↔ …);
        // горизонтальные прокрутки и звёзды оценки внутри экранов забирают жест себе раньше
        val swipeTab = tabs.indexOfFirst { it.route == route }
        val swipeModifier = if (swipeTab < 0) Modifier else Modifier.pointerInput(swipeTab) {
            val threshold = 72.dp.toPx()
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    val target = when {
                        total <= -threshold -> swipeTab + 1
                        total >= threshold -> swipeTab - 1
                        else -> -1
                    }
                    tabs.getOrNull(target)?.let { t -> goTab(t.route); currentTab = t.route }
                },
            ) { change, dx ->
                change.consume()
                total += dx
            }
        }
        NavHost(nav, startDestination = "shelf", modifier = Modifier.padding(pad).then(swipeModifier)) {
            composable("shelf") {
                ShelfScreen(
                    onBook = { nav.openBook(it) },
                    onAuthor = { nav.openAuthor(it) },
                    onSearch = { goTab("search"); currentTab = "search" },
                )
            }
            composable("library") {
                LibraryScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("reader/$id") { popUpTo("library") { inclusive = true } } },
                )
            }
            composable("reader/{id}") { e ->
                ReaderScreen(
                    bookId = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onLibrary = { nav.navigate("library") },
                    onShelfBook = { nav.openBook(it) },
                )
            }
            composable("authors") {
                AuthorsCloudScreen(
                    onAuthor = { nav.openAuthor(it) },
                    onGenre = {
                        showRecsForGenre(it)
                        goTab("recs")
                    },
                )
            }
            composable("recs") { RecommendationsScreen(onBook = { nav.openBook(it) }) }
            composable("feed") {
                FeedScreen(
                    onBook = { nav.openBook(it) },
                    onUser = { nav.openUser(it) },
                    onPeople = { nav.navigate("people") },
                    onProfile = { nav.navigate("profile") },
                )
            }
            composable("people") { PeopleScreen(onBack = { nav.popBackStack() }, onUser = { nav.openUser(it) }) }
            composable("user/{id}") { e ->
                UserScreen(
                    id = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onBook = { nav.openBook(it) },
                    onUser = { nav.openUser(it) },
                )
            }
            composable("search") {
                SearchScreen(onBook = { nav.openBook(it) }, onAuthor = { nav.openAuthor(it) })
            }
            composable("profile") {
                ProfileScreen(
                    onBook = { nav.openBook(it) },
                    onUser = { nav.openUser(it) },
                    onPeople = { nav.navigate("people") },
                    onAuthor = { nav.openAuthor(it) },
                    onShelf = { st ->
                        ShelfUi.tab = st.ordinal
                        goTab("shelf")
                        currentTab = "shelf"
                    },
                )
            }
            composable("book/{id}") { e ->
                BookScreen(
                    id = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onBook = { nav.openBook(it) },
                    onAuthor = { nav.openAuthor(it) },
                    onRead = { nav.navigate("reader/$it") },
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
}


/** Кнопка-логотип в нижнем меню: цветной логотип в круге цвета панели, крупнее соседних значков. */
@Composable
private fun ReaderLogoButton() {
    Box(
        Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.material3.NavigationBarDefaults.containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(ru.moistranicy.app.R.drawable.ic_launcher_background), "Читалка",
                modifier = Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(ru.moistranicy.app.R.drawable.ic_launcher_foreground), null,
                modifier = Modifier.matchParentSize().scale(1.35f), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}
