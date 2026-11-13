@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package ru.moistranicy.app.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.moistranicy.app.data.reader.BookImporter
import ru.moistranicy.app.data.reader.LibBook
import ru.moistranicy.app.data.reader.Library
import ru.moistranicy.app.data.reader.Linker
import ru.moistranicy.app.data.reader.ReaderPrefs
import java.io.File
import kotlin.math.roundToInt

/** Цвета тем читалки: фон и текст. */
private val READER_THEMES = listOf(
    "Светлая" to (Color(0xFFFFFFFF) to Color(0xFF1B1B1F)),
    "Сепия" to (Color(0xFFF4ECD8) to Color(0xFF5B4636)),
    "Тёмная" to (Color(0xFF121212) to Color(0xFFD6D2E0)),
)

private fun hex(c: Color) = "#%06X".format(0xFFFFFF and c.toArgb())

/** Стили читателя поверх стилей книги + разбиение на страницы колонками. */
private fun readerCss(): String {
    val (bg, fg) = READER_THEMES[ReaderPrefs.theme].second
    val m = ReaderPrefs.margin
    val font = if (ReaderPrefs.serif) "'Noto Serif', Georgia, serif" else "Roboto, 'Noto Sans', sans-serif"
    return """
html{height:100%!important;overflow:hidden!important;margin:0!important;padding:0!important;background:${hex(bg)}!important;}
body{margin:0!important;padding:20px ${m}px!important;box-sizing:border-box!important;height:100vh!important;
-webkit-column-width:calc(100vw - ${2 * m}px)!important;column-width:calc(100vw - ${2 * m}px)!important;
-webkit-column-gap:${2 * m}px!important;column-gap:${2 * m}px!important;column-fill:auto!important;
background:${hex(bg)}!important;color:${hex(fg)}!important;font-size:${ReaderPrefs.fontSize}px!important;
line-height:${ReaderPrefs.lineHeight}!important;font-family:$font!important;
text-align:${if (ReaderPrefs.justify) "justify" else "left"}!important;-webkit-hyphens:auto;hyphens:auto;
overflow-wrap:break-word;widows:2;orphans:2;}
body *{color:inherit!important;background-color:transparent!important;font-family:inherit!important;max-width:100%;}
p,div,li,blockquote{line-height:inherit!important}
img,svg,image{max-width:100%!important;max-height:calc(100vh - 40px)!important;height:auto;object-fit:contain;break-inside:avoid}
a{text-decoration:none!important}
h1,h2,h3,h4,.title{break-after:avoid}
""".replace("\n", "")
}

/** Скрипт страниц: подставляет стили, считает страницы, листает. */
private const val READER_JS = """
(function(){
 var R = window.__rd || (window.__rd = {});
 R.count = function(){ var w = window.innerWidth; return Math.max(1, Math.ceil((document.documentElement.scrollWidth - 2) / w)); };
 R.report = function(){ RdBridge.onLayout(R.count()); };
 R.go = function(p){ var x = p * window.innerWidth; window.scrollTo(x, 0); document.documentElement.scrollLeft = x; document.body.scrollLeft = x; };
 R.apply = function(css){
  var s = document.getElementById('__rdcss');
  if (!s) { s = document.createElement('style'); s.id = '__rdcss'; (document.head || document.documentElement).appendChild(s); }
  s.textContent = css;
  if (!document.documentElement.lang) document.documentElement.lang = 'ru';
  setTimeout(R.report, 80);
 };
 var imgs = document.images;
 for (var i = 0; i < imgs.length; i++) if (!imgs[i].complete) imgs[i].addEventListener('load', function(){ setTimeout(R.report, 40); });
})();
"""

private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onLibrary: () -> Unit, onShelfBook: (String) -> Unit) {
    val books by Library.books.collectAsState()
    val book = books[bookId]
    if (book == null || book.chapters.isEmpty()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            CenterMessage("Книга не найдена в библиотеке", "Открыть библиотеку") { onLibrary() }
        }
        return
    }
    val current by rememberUpdatedState(book)
    val scope = rememberCoroutineScope()
    var chapter by remember(bookId) { mutableIntStateOf(book.chapter.coerceIn(0, book.chapters.lastIndex)) }
    var page by remember(bookId) { mutableIntStateOf(0) }
    var pages by remember(bookId) { mutableIntStateOf(1) }
    /** Куда встать после вёрстки главы: доля 0..1, -1 — последняя страница. */
    var landing by remember(bookId) { mutableStateOf<Float?>(book.frac) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var finishDialog by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val (bg, fg) = READER_THEMES[ReaderPrefs.theme].second

    // открытие: книга — последняя прочитанная, на полке — «Читаю»
    LaunchedEffect(bookId) {
        Library.update(bookId) { it.copy(openedAt = System.currentTimeMillis()) }
        withContext(Dispatchers.IO) { runCatching { Linker.onOpen(book) } }
    }

    fun save() {
        val frac = if (pages <= 1) 0f else page.toFloat() / pages
        Library.savePosition(bookId, chapter, frac)
    }

    fun loadChapter(i: Int, frac: Float) {
        val b = current
        chapter = i.coerceIn(0, b.chapters.lastIndex)
        landing = frac
        val f = File(Library.dirOf(bookId), b.chapters[chapter].path)
        web?.loadUrl("file://" + f.absolutePath)
    }

    fun atEnd() = chapter == current.chapters.lastIndex && page >= pages - 1

    fun go(p: Int) {
        page = p.coerceIn(0, pages - 1)
        web?.evaluateJavascript("window.__rd && __rd.go($page)", null)
        save()
        if (atEnd() && !current.finishAsked) finishDialog = true
    }

    fun next() {
        if (menu) { menu = false; return }
        when {
            page < pages - 1 -> go(page + 1)
            chapter < current.chapters.lastIndex -> loadChapter(chapter + 1, 0f)
            else -> finishDialog = true
        }
    }

    fun prev() {
        if (menu) { menu = false; return }
        when {
            page > 0 -> go(page - 1)
            chapter > 0 -> loadChapter(chapter - 1, -1f)
        }
    }

    // вёрстка главы готова: число страниц и нужная страница
    val onLayout = remember(bookId) {
        { n: Int ->
            pages = n.coerceAtLeast(1)
            val l = landing
            val target = when {
                l == null -> page
                l < 0f -> pages - 1
                else -> (l * pages + 0.001f).toInt()
            }
            landing = null
            go(target)
        }
    }
    val bridge = remember(bookId) {
        object {
            val main = Handler(Looper.getMainLooper())
            @JavascriptInterface
            fun onLayout(n: Int) { main.post { onLayout(n) } }
        }
    }

    // настройки изменились — перевёрстка с сохранением места
    val css = readerCss()
    LaunchedEffect(css) {
        val w = web ?: return@LaunchedEffect
        landing = if (pages <= 1) 0f else page.toFloat() / pages
        w.setBackgroundColor(bg.toArgb())
        w.evaluateJavascript("window.__rd && __rd.apply(${jsString(css)})", null)
    }

    DisposableEffect(bookId) { onDispose { save() } }

    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.systemBars)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            keepScreenOn = true
                            setBackgroundColor(bg.toArgb())
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = View.OVER_SCROLL_NEVER
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.textZoom = 100
                            @Suppress("JavascriptInterface")
                            addJavascriptInterface(bridge, "RdBridge")
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                override fun onPageFinished(view: WebView, url: String?) {
                                    view.evaluateJavascript(READER_JS, null)
                                    view.evaluateJavascript("__rd.apply(${jsString(readerCss())})", null)
                                }
                            }
                            web = this
                            val f = File(Library.dirOf(bookId), book.chapters[chapter].path)
                            loadUrl("file://" + f.absolutePath)
                        }
                    },
                )
                // жесты: тап слева/справа и свайп — листание; тап по центру и долгое нажатие — меню
                val density = LocalDensity.current
                val swipe = with(density) { 56.dp.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bookId) {
                            detectTapGestures(
                                onTap = { o ->
                                    when {
                                        o.x < size.width * 0.3f -> prev()
                                        o.x > size.width * 0.7f -> next()
                                        else -> menu = !menu
                                    }
                                },
                                onLongPress = { menu = !menu },
                            )
                        }
                        .pointerInput(bookId) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = { if (total < -swipe) next() else if (total > swipe) prev() },
                            ) { change, dx -> change.consume(); total += dx }
                        },
                )
            }
            // строка состояния: глава и прогресс
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current.chapters.getOrNull(chapter)?.title.orEmpty(),
                    fontSize = 11.sp, color = fg.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${page + 1}/$pages · ${(current.progress * 100).roundToInt()}%",
                    fontSize = 11.sp, color = fg.copy(alpha = 0.55f),
                )
            }
        }

        if (menu) {
            // верхняя строка
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current.author.isNotEmpty()) {
                        Text(current.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                current.linkedId?.let { lid ->
                    TextButton(onClick = { onShelfBook(lid) }) { Text("О книге") }
                }
            }
            // нижняя панель
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                var seek by remember { mutableFloatStateOf(current.progress) }
                var seeking by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = if (seeking) seek else current.progress,
                        onValueChange = { seeking = true; seek = it },
                        onValueChangeFinished = {
                            seeking = false
                            // доля книги → глава и место в ней
                            val b = current
                            var acc = 0L
                            val target = (seek * b.total).toLong()
                            var idx = b.chapters.lastIndex
                            for ((i, c) in b.chapters.withIndex()) {
                                if (acc + c.size > target) { idx = i; break }
                                acc += c.size
                            }
                            val frac = ((target - acc).toFloat() / b.chapters[idx].size).coerceIn(0f, 0.999f)
                            loadChapter(idx, frac)
                        },
                        colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${((if (seeking) seek else current.progress) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    MenuButton("Оглавление") { sheet = "toc" }
                    MenuButton("Шрифт и тема") { sheet = "font" }
                    MenuButton("Библиотека") { onLibrary() }
                }
            }
        }
    }

    when (sheet) {
        "toc" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (chapter - 3).coerceAtLeast(0))
            LazyColumn(state = state, modifier = Modifier.padding(bottom = 24.dp)) {
                itemsIndexed(current.chapters) { i, c ->
                    Text(
                        c.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (i == chapter) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == chapter) Violet else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable {
                            sheet = null; menu = false; loadChapter(i, 0f)
                        }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
        "font" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Размер шрифта", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { ReaderPrefs.set(fontSize = ReaderPrefs.fontSize - 1) }) { Text("А−") }
                    Text("${ReaderPrefs.fontSize}", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { ReaderPrefs.set(fontSize = ReaderPrefs.fontSize + 1) }) { Text("А+") }
                }
                Spacer(Modifier.height(10.dp))
                Text("Шрифт", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(ReaderPrefs.serif, { ReaderPrefs.set(serif = true) }, { Text("С засечками") })
                    FilterChip(!ReaderPrefs.serif, { ReaderPrefs.set(serif = false) }, { Text("Без засечек") })
                    FilterChip(ReaderPrefs.justify, { ReaderPrefs.set(justify = !ReaderPrefs.justify) }, { Text("По ширине") })
                }
                Spacer(Modifier.height(6.dp))
                Text("Межстрочный интервал", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Плотный" to 1.3f, "Обычный" to 1.5f, "Свободный" to 1.8f).forEach { (l, v) ->
                        FilterChip(ReaderPrefs.lineHeight == v, { ReaderPrefs.set(lineHeight = v) }, { Text(l) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Поля", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Узкие" to 10, "Средние" to 18, "Широкие" to 32).forEach { (l, v) ->
                        FilterChip(ReaderPrefs.margin == v, { ReaderPrefs.set(margin = v) }, { Text(l) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Тема", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    READER_THEMES.forEachIndexed { i, (l, _) ->
                        FilterChip(ReaderPrefs.theme == i, { ReaderPrefs.set(theme = i) }, { Text(l) })
                    }
                }
            }
        }
    }

    if (finishDialog) {
        AlertDialog(
            onDismissRequest = { finishDialog = false; Library.update(bookId) { it.copy(finishAsked = true) } },
            title = { Text("Книга дочитана") },
            text = {
                Text(
                    if (current.linkedId != null) "Отметить «${current.title}» прочитанной? На полке можно будет поставить оценку."
                    else "Вы дошли до конца «${current.title}».",
                )
            },
            confirmButton = {
                if (current.linkedId != null) {
                    Button(onClick = {
                        finishDialog = false
                        Library.update(bookId) { it.copy(finishAsked = true) }
                        scope.launch {
                            val id = withContext(Dispatchers.IO) { runCatching { Linker.markRead(current) }.getOrNull() }
                            if (id != null) onShelfBook(id)
                        }
                    }) { Text("Прочитал") }
                } else {
                    TextButton(onClick = { finishDialog = false; Library.update(bookId) { it.copy(finishAsked = true) } }) { Text("Хорошо") }
                }
            },
            dismissButton = if (current.linkedId != null) {
                { TextButton(onClick = { finishDialog = false; Library.update(bookId) { it.copy(finishAsked = true) } }) { Text("Не сейчас") } }
            } else null,
        )
    }
}

@Composable
private fun MenuButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/** Библиотека читалки: книги-файлы, добавление и удаление. */
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val books by Library.books.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<LibBook?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = null
            try {
                val b = BookImporter.import(context, uri)
                onOpen(b.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось открыть книгу"
            } finally {
                importing = false
            }
        }
    }
    val list = books.values.sortedWith(compareByDescending<LibBook> { it.openedAt }.thenByDescending { it.addedAt })

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Моя библиотека") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            Button(
                onClick = { pick.launch(arrayOf("*/*")) },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить книгу (FB2, EPUB)")
            }
            if (list.isEmpty()) {
                CenterMessage("Добавьте книгу в формате FB2 или EPUB — она откроется в читалке, а на полке встанет в «Читаю».")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { onOpen(b.id) }, onLongClick = { toDelete = b })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(width = 48.dp, height = 70.dp).clip(RoundedCornerShape(5.dp))
                                    .background(Violet.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(b.title.take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold)
                                b.cover?.let { c ->
                                    AsyncImage(File(Library.dirOf(b.id), c), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(b.author.ifEmpty { null }, b.series?.let { s -> s + (b.seriesNo?.let { " #$it" } ?: "") }).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { b.progress },
                                        color = Violet,
                                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("${(b.progress * 100).roundToInt()}% · ${b.format}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                    item {
                        Text(
                            "Долгое нажатие на книгу — удалить её из библиотеки.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить из библиотеки?") },
            text = { Text("Файл «${b.title}» будет удалён с телефона. Книга на полке останется.") },
            confirmButton = { TextButton(onClick = { Library.remove(b.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
