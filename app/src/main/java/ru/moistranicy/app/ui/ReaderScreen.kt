@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package ru.moistranicy.app.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Settings
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

/** Открыто ли меню читалки: тогда снизу видна основная панель приложения. */
object ReaderUi {
    var menu by mutableStateOf(false)
}

/** Цвета тем читалки: фон и текст. */
private val READER_THEMES = listOf(
    "Светлая" to (Color(0xFFFFFFFF) to Color(0xFF1B1B1F)),
    "Сепия" to (Color(0xFFE4CFA2) to Color(0xFF3A2816)),
    "Тёмная" to (Color(0xFF121212) to Color(0xFFD6D2E0)),
)

/**
 * Текстура старой бумаги для «Сепии»: пятнистые разводы (шум нескольких масштабов), мелкое зерно
 * и затемнённые края. Рисуется один раз в малом размере и растягивается — пятна выходят мягкими.
 */
private fun parchment(w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
    val bw = 270; val bh = (bw * h.toFloat() / w.coerceAtLeast(1)).toInt().coerceIn(200, 900)
    val rnd = java.util.Random(7)
    // шум: случайные значения в узлах сетки, между ними — плавная интерполяция
    fun layer(cells: Int): (Float, Float) -> Float {
        val gw = cells + 2; val gh = (cells * bh / bw) + 2
        val g = FloatArray(gw * gh) { rnd.nextFloat() }
        return { x, y ->
            val fx = x / bw * cells; val fy = y / bw * cells
            val ix = fx.toInt().coerceIn(0, gw - 2); val iy = fy.toInt().coerceIn(0, gh - 2)
            val tx = (fx - ix).let { it * it * (3 - 2 * it) }; val ty = (fy - iy).let { it * it * (3 - 2 * it) }
            val a = g[iy * gw + ix]; val b = g[iy * gw + ix + 1]
            val c = g[(iy + 1) * gw + ix]; val d = g[(iy + 1) * gw + ix + 1]
            (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
        }
    }
    val l1 = layer(3); val l2 = layer(7); val l3 = layer(16); val l4 = layer(40)
    val px = IntArray(bw * bh)
    for (y in 0 until bh) for (x in 0 until bw) {
        val n = 0.45f * l1(x.toFloat(), y.toFloat()) + 0.3f * l2(x.toFloat(), y.toFloat()) +
            0.17f * l3(x.toFloat(), y.toFloat()) + 0.08f * l4(x.toFloat(), y.toFloat())
        val stain = ((n - 0.45f) * 2.2f).coerceIn(0f, 1f)           // тёмные разводы
        val dx = (x - bw / 2f) / (bw / 2f); val dy = (y - bh / 2f) / (bh / 2f)
        val edge = ((dx * dx + dy * dy) / 2f).coerceIn(0f, 1f)        // края темнее
        val grain = (rnd.nextFloat() - 0.5f) * 0.045f                  // мелкое зерно
        val k = (1f - 0.17f * stain - 0.24f * edge * edge + grain).coerceIn(0.55f, 1.06f)
        val r = (231 * k).toInt().coerceIn(0, 255); val gg = (210 * k).toInt().coerceIn(0, 255); val bb = (163 * k * (1f - 0.04f * stain)).toInt().coerceIn(0, 255)
        px[y * bw + x] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or bb
    }
    val bmp = android.graphics.Bitmap.createBitmap(px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
    return android.graphics.Bitmap.createScaledBitmap(bmp, w.coerceAtLeast(1), h.coerceAtLeast(1), true).asImageBitmap()
}

private fun hex(c: Color) = "#%06X".format(0xFFFFFF and c.toArgb())

/** Стили читателя поверх стилей книги + разбиение на страницы колонками. */
private fun readerCss(w: Int, h: Int): String {
    val (bg, fg) = READER_THEMES[ReaderPrefs.theme].second
    val m = ReaderPrefs.margin
    // сепия: фон страницы рисует приложение (текстура бумаги под WebView), сама страница прозрачная
    val pageBg = if (ReaderPrefs.theme == 1) "transparent" else hex(bg)
    val font = if (ReaderPrefs.serif) "'Noto Serif', Georgia, serif" else "Roboto, 'Noto Sans', sans-serif"
    return """
html{width:${w}px!important;height:${h}px!important;overflow:hidden!important;margin:0!important;padding:0!important;background-color:$pageBg!important;}
body{margin:0!important;padding:20px ${m}px!important;box-sizing:border-box!important;width:${w}px!important;height:${h}px!important;
-webkit-column-width:${w - 2 * m}px!important;column-width:${w - 2 * m}px!important;
-webkit-column-gap:${2 * m}px!important;column-gap:${2 * m}px!important;column-fill:auto!important;
background:transparent!important;color:${hex(fg)}!important;font-size:${ReaderPrefs.fontSize}px!important;
line-height:${ReaderPrefs.lineHeight}!important;font-family:$font!important;
text-align:${if (ReaderPrefs.justify) "justify" else "left"}!important;-webkit-hyphens:auto;hyphens:auto;
overflow-wrap:break-word;widows:2;orphans:2;}
body *{color:inherit!important;background-color:transparent!important;font-family:inherit!important;max-width:100%;}
p,div,li,blockquote{line-height:inherit!important}
img,svg,image{max-width:100%!important;max-height:${h - 40}px!important;height:auto;object-fit:contain;break-inside:avoid}
a{text-decoration:none!important}
h1,h2,h3,h4,.title{break-after:avoid}
""".replace("\n", "")
}

/** Скрипт страниц: подставляет стили, считает страницы, листает. */
private const val READER_JS = """
(function(){
 var R = window.__rd || (window.__rd = {});
 R.count = function(){
  var w = R.w || window.innerWidth;
  // ширина всех колонок — по body: свой сдвиг (transform) на неё не влияет
  var sw = document.body ? document.body.scrollWidth : 0;
  if (!sw || sw < w) sw = document.documentElement.scrollWidth;
  return Math.max(1, Math.ceil((sw - 2) / w));
 };
 R.report = function(){
  RdBridge.onLayout(R.count());
 };
 if (!R.resizeHooked) { R.resizeHooked = true; window.addEventListener('resize', function(){ setTimeout(R.report, 60); }); }
 // листаем сдвигом body, а не прокруткой: прокрутку браузер обрезает на последней странице главы,
 // и текст уезжал вправо; сдвиг ещё и анимируется — страница «съезжает» как при перелистывании
 R.go = function(p, anim){
  var x = p * (R.w || window.innerWidth), b = document.body;
  window.scrollTo(0, 0); document.documentElement.scrollLeft = 0;
  b.style.transition = anim ? 'transform 230ms cubic-bezier(.2,.7,.3,1)' : 'none';
  b.style.transform = 'translate3d(' + (-x) + 'px,0,0)';
 };
 R.apply = function(css, w){
  R.w = w;
  var vp = document.querySelector('meta[name=viewport]');
  if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; (document.head || document.documentElement).appendChild(vp); }
  vp.content = 'width=' + w + ',initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no';
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
    var menu by ReaderUi::menu
    DisposableEffect(Unit) { onDispose { ReaderUi.menu = false } }
    var sheet by remember { mutableStateOf<String?>(null) }
    var finishDialog by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    /** Размер страницы в CSS-пикселях (= dp). */
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
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

    fun go(p: Int, anim: Boolean = true) {
        page = p.coerceIn(0, pages - 1)
        web?.evaluateJavascript("window.__rd && __rd.go($page, ${anim})", null)
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
            go(target, anim = false)
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
    val css = readerCss(viewW, viewH)
    LaunchedEffect(css) {
        val w = web ?: return@LaunchedEffect
        if (viewW <= 0 || viewH <= 0) return@LaunchedEffect
        // место ещё не восстановлено (первая вёрстка впереди) — не перебиваем его нулём
        if (landing == null) landing = if (pages <= 1) 0f else page.toFloat() / pages
        w.setBackgroundColor(if (ReaderPrefs.theme == 1) android.graphics.Color.TRANSPARENT else bg.toArgb())
        w.evaluateJavascript("window.__rd && __rd.apply(${jsString(css)}, $viewW)", null)
    }

    DisposableEffect(bookId) { onDispose { save() } }

    // назад при открытом меню — закрыть меню
    androidx.activity.compose.BackHandler(enabled = menu) { menu = false }
    // с открытым меню снизу видна основная панель приложения — отступ только сверху
    val insets = if (menu) WindowInsets.statusBars else WindowInsets.systemBars
    val sepia = ReaderPrefs.theme == 1
    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val paper = remember(sepia, config.screenWidthDp, config.screenHeightDp) {
        if (!sepia) null else with(density) {
            // весь экран целиком: текстура не зависит от отступов и прокрутки страниц
            parchment((config.screenWidthDp.dp.toPx() / 2).toInt(), ((config.screenHeightDp + 80).dp.toPx() / 2).toInt())
        }
    }
    Box(
        Modifier.fillMaxSize().background(bg)
            .then(
                if (paper != null) Modifier.drawBehind {
                    drawImage(paper, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                } else Modifier,
            )
            .windowInsetsPadding(insets),
    ) {
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                viewW = maxWidth.value.toInt()
                viewH = maxHeight.value.toInt()
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            keepScreenOn = true
                            setBackgroundColor(if (ReaderPrefs.theme == 1) android.graphics.Color.TRANSPARENT else bg.toArgb())
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = View.OVER_SCROLL_NEVER
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.textZoom = 100
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = false
                            @Suppress("JavascriptInterface")
                            addJavascriptInterface(bridge, "RdBridge")
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                    android.util.Log.i("MoiReader", m.message() + " @" + m.lineNumber())
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                override fun onPageFinished(view: WebView, url: String?) {
                                    view.evaluateJavascript(READER_JS, null)
                                    if (viewW > 0 && viewH > 0) {
                                        view.evaluateJavascript("__rd.apply(${jsString(readerCss(viewW, viewH))}, $viewW)", null)
                                    }
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
                                        // меню — только долгим нажатием; тап по центру закрывает открытое меню
                                        else -> if (menu) menu = false
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
                // страницы всей книги: по числу страниц текущей главы и весу глав (оценка)
                val curSize = current.chapters.getOrNull(chapter)?.size?.coerceAtLeast(1) ?: 1
                val before = current.chapters.take(chapter).sumOf { it.size }
                val bookPages = ((pages.toDouble() * current.total / curSize).roundToInt()).coerceAtLeast(pages)
                val bookPage = ((before.toDouble() / curSize * pages).roundToInt() + page + 1).coerceIn(1, bookPages)
                Text(
                    "$bookPage из $bookPages · ${(current.progress * 100).roundToInt()}%",
                    fontSize = 11.sp, color = fg.copy(alpha = 0.55f),
                )
            }
        }

        if (menu) {
            Column(Modifier.fillMaxSize().background(bg)) {
                // верхняя строка: книга
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = fg) }
                    Column(Modifier.weight(1f)) {
                        Text(current.title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current.author.isNotEmpty()) {
                            Text(current.author, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.6f), maxLines = 1)
                        }
                    }
                    current.linkedId?.let { lid ->
                        TextButton(onClick = { onShelfBook(lid) }) { Text("О книге") }
                    }
                }
                // вместо текста — оглавление
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (chapter - 3).coerceAtLeast(0))
                LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(current.chapters) { i, c ->
                        Text(
                            c.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (i == chapter) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == chapter) Coral else fg,
                            modifier = Modifier.fillMaxWidth().clickable {
                                menu = false; loadChapter(i, 0f)
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                // внизу: шестерёнка (шрифт и тема) и шкала прогресса с оранжевой палочкой
                var seek by remember { mutableFloatStateOf(current.progress) }
                var seeking by remember { mutableStateOf(false) }
                Row(
                    // под нижней «капсулой» разделов: ползунок над ней
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 4.dp + barSpace()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { menu = false; sheet = "font" }) {
                        Icon(Icons.Filled.Settings, "Шрифт и тема", tint = Color(0xFF5A5766))
                    }
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
                            menu = false
                            loadChapter(idx, frac)
                        },
                        colors = SliderDefaults.colors(thumbColor = Coral, activeTrackColor = Violet),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${((if (seeking) seek else current.progress) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = fg)
                }
            }
        }
    }

    when (sheet) {
        // настройки поверх текста: видно, как меняется страница; «Назад» — сразу к чтению
        "font" -> ModalBottomSheet(onDismissRequest = { sheet = null; menu = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sheet = null; menu = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К чтению") }
                    Text("Настройки чтения", style = MaterialTheme.typography.titleMedium)
                }
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

    // дочитали: спрашиваем, отметить ли книгу прочитанной; да — «Прочитал» и предложение оценить
    var markedId by remember(bookId) { mutableStateOf<String?>(null) }
    var marking by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(finishDialog) {
        if (finishDialog && !current.finishAsked) Library.update(bookId) { it.copy(finishAsked = true) }
    }
    val finishScope = rememberCoroutineScope()
    if (finishDialog) {
        val done = markedId
        AlertDialog(
            onDismissRequest = { finishDialog = false },
            title = { Text(if (done == null) "Книга дочитана" else "Книга прочитана") },
            text = {
                Text(
                    if (done == null) "Отметить «${current.title}» как прочитанную? Она переместится из «Читаю» в «Прочитал»."
                    else "«${current.title}» на полке «Прочитал». Можно поставить оценку.",
                )
            },
            confirmButton = {
                if (done == null) {
                    Button(enabled = !marking, onClick = {
                        marking = true
                        finishScope.launch {
                            markedId = withContext(Dispatchers.IO) { runCatching { Linker.markRead(current) }.getOrNull() }
                            marking = false
                            if (markedId == null) finishDialog = false
                        }
                    }) { Text(if (marking) "Отмечаю…" else "Да, прочитана") }
                } else {
                    Button(onClick = { finishDialog = false; onShelfBook(done) }) { Text("Оценить") }
                }
            },
            dismissButton = {
                TextButton(onClick = { finishDialog = false }) { Text(if (done == null) "Нет" else "Закрыть") }
            },
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
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barSpace())) {
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


/** Кнопка «Добавить книгу (FB2, EPUB)»: выбор файла, импорт и сразу в читалку. */
@Composable
fun AddBookButton(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = null
            try {
                onOpen(BookImporter.import(context, uri).id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось открыть книгу"
            } finally {
                importing = false
            }
        }
    }
    Column(modifier) {
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(if (importing) "Открываю книгу…" else "Добавить книгу (FB2, EPUB)")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Книга-файл во «Читаю»: обложка, название, автор и сколько прочитано. Долгое нажатие — удалить файл. */
@Composable
fun FileBookRow(
    b: LibBook,
    shelfCover: ru.moistranicy.app.data.Book?,
    onOpen: () -> Unit,
    onCycle: ((String) -> Unit)? = null,
    onAuthor: ((String) -> Unit)? = null,
) {
    var ask by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { ask = true })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 60.dp, height = 88.dp).clip(RoundedCornerShape(6.dp)).background(Violet.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center) {
            when {
                b.cover != null -> AsyncImage(File(Library.dirOf(b.id), b.cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                shelfCover != null -> BookCover(shelfCover, Modifier.fillMaxSize(), corner = 6.dp)
                else -> Text(b.title.take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // цикл и автор — ссылки: на страницу цикла и автора (если книга найдена на FantLab)
            val seriesName = shelfCover?.seriesName ?: b.series
            val seriesNo = shelfCover?.seriesNumber ?: b.seriesNo
            val cycleId = shelfCover?.seriesId
            if (seriesName != null) Text(
                "Цикл «$seriesName»" + (seriesNo?.let { ", книга $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (cycleId != null && onCycle != null) Modifier.clickable { onCycle(cycleId) } else Modifier,
            )
            val author = shelfCover?.authors?.firstOrNull { it.id != null }
            val authorLine = shelfCover?.authorsLine?.takeIf { it.isNotEmpty() } ?: b.author
            if (authorLine.isNotEmpty()) Text(
                authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                modifier = if (author?.id != null && onAuthor != null) Modifier.clickable { onAuthor(author.id!!) } else Modifier,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { b.progress }, color = Violet,
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("${(b.progress * 100).roundToInt()}% · ${b.format}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Удалить книгу?") },
            text = { Text("«${b.title}» будет удалена полностью: файл с телефона и книга с полки (статус, оценка, дата прочтения).") },
            confirmButton = {
                TextButton(onClick = {
                    b.linkedId?.let { lid ->
                        ru.moistranicy.app.data.ShelfRepository.remove(lid)
                        ru.moistranicy.app.data.CycleRepository.onBookRemoved(lid)
                    }
                    Library.remove(b.id)
                    ask = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } },
        )
    }
}
