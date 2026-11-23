@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.Manifest
import android.content.Intent
import kotlinx.coroutines.launch
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
        ru.moistranicy.app.data.reader.Opds.init(applicationContext)
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

private data class Tab(val route: String, val label: String, val icon: ImageVector?, val res: Int? = null)

private val tabs = listOf(
    Tab("shelf", "Полка", null, ru.moistranicy.app.R.drawable.ic_bookshelf),
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
            openRequest.startsWith("route:") -> nav.navigate(openRequest.removePrefix("route:"))
        }
        if (openRequest != null) onOpenHandled()
    }

    // книга, открытая через «Открыть с помощью», — в библиотеку и сразу в читалку
    var importError by remember { mutableStateOf<String?>(null) }
    val importScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(importUri) {
        val u = importUri ?: return@LaunchedEffect
        // импорт — в своей корутине: сброс importUri не должен его отменять
        importScope.launch {
            try {
                val b = ru.moistranicy.app.data.reader.BookImporter.import(context, u)
                nav.navigate("reader/${b.id}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = e.message ?: "Не удалось открыть книгу"
            }
        }
        onImportHandled()
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
        ReaderUi.menu = false
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
            if (!inReader || ReaderUi.menu) {
                fun onTab(t: Tab) {
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
                }
                FloatingTabBar(
                    selected = route,
                    newCount = newCount,
                    onTab = ::onTab,
                    onReader = { openReader() },
                )
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
        // переход между главными разделами — сдвигом в сторону соседнего раздела;
        // остальные экраны (книга, автор…) появляются плавным проявлением
        fun tabIdx(r: String?) = tabs.indexOfFirst { it.route == r }
        fun dir(from: String?, to: String?): Int {
            val a = tabIdx(from); val b = tabIdx(to)
            return if (a < 0 || b < 0 || a == b) 0 else if (b > a) 1 else -1
        }
        val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(260)
        val fade = androidx.compose.animation.core.tween<Float>(200)
        NavHost(
            nav, startDestination = "shelf", modifier = Modifier.padding(pad).then(swipeModifier),
            enterTransition = {
                when (val d = dir(initialState.destination.route, targetState.destination.route)) {
                    0 -> androidx.compose.animation.fadeIn(fade)
                    else -> androidx.compose.animation.slideInHorizontally(slide) { w -> d * w }
                }
            },
            exitTransition = {
                when (val d = dir(initialState.destination.route, targetState.destination.route)) {
                    0 -> androidx.compose.animation.fadeOut(fade)
                    else -> androidx.compose.animation.slideOutHorizontally(slide) { w -> -d * w }
                }
            },
            popEnterTransition = { androidx.compose.animation.fadeIn(fade) },
            popExitTransition = { androidx.compose.animation.fadeOut(fade) },
        ) {
            composable("shelf") {
                ShelfScreen(
                    onBook = { nav.openBook(it) },
                    onAuthor = { nav.openAuthor(it) },
                    onSearch = { goTab("search"); currentTab = "search" },
                    onRead = { nav.navigate("reader/$it") },
                    onOpds = { nav.navigate("opds") },
                )
            }
            composable("opds") {
                OpdsScreen(onBack = { nav.popBackStack() }, onOpenBook = { id -> nav.navigate("reader/$id") })
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
                    onOpds = { nav.navigate("opds") },
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


/**
 * Нижняя панель-«капсула»: висит над экраном со скруглёнными краями, без подписей.
 * По центру — круг с логотипом, выступающий над капсулой: открывает читалку.
 */
@Composable
private fun FloatingTabBar(selected: String?, newCount: Int, onTab: (Tab) -> Unit, onReader: () -> Unit) {
    val dark = isAppDark()
    val barColor = if (dark) CColor(0xFF242130) else Color.White
    val border = if (dark) CColor(0x22FFFFFF) else CColor(0x14000000)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 8.dp).height(74.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp)
                .shadow(10.dp, pill, ambientColor = CColor(0x40000000), spotColor = CColor(0x40000000))
                .clip(pill).background(barColor)
                .border(1.dp, border, pill)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, t ->
                if (i == 2) Spacer(Modifier.width(76.dp))
                val on = selected == t.route
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { onTab(t) }
                        .semantics { contentDescription = t.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 52.dp, height = 38.dp).clip(pill)
                            .background(if (on) Violet.copy(alpha = if (dark) 0.30f else 0.14f) else CColor.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (on) Violet else MaterialTheme.colorScheme.onSurfaceVariant
                        val icon: @Composable () -> Unit = {
                            if (t.res != null) Icon(androidx.compose.ui.res.painterResource(t.res), null, tint = tint, modifier = Modifier.size(25.dp))
                            else Icon(t.icon!!, null, tint = tint, modifier = Modifier.size(25.dp))
                        }
                        if (t.route == "shelf" && newCount > 0) BadgedBox(badge = { Badge { Text("$newCount") } }) { icon() } else icon()
                    }
                }
            }
        }
        // круг с логотипом, выступающий над капсулой
        Box(
            Modifier.size(74.dp)
                .shadow(10.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = CColor(0x40000000), spotColor = CColor(0x40000000))
                .clip(androidx.compose.foundation.shape.CircleShape).background(barColor)
                .border(1.dp, border, androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onReader)
                .semantics { contentDescription = "Читалка" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp))) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(ru.moistranicy.app.R.drawable.ic_launcher_background), null,
                    modifier = Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(ru.moistranicy.app.R.drawable.ic_launcher_foreground), null,
                    modifier = Modifier.matchParentSize().scale(1.35f), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
    }
}
