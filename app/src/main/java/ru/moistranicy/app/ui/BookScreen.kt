@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import ru.moistranicy.app.data.cycleBooks
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.ExternalSources
import ru.moistranicy.app.data.BookCache
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.GoogleBooks
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import ru.moistranicy.app.data.WorkDetails
import ru.moistranicy.app.data.isHiddenType
import ru.moistranicy.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.BoxWithConstraints
import ru.moistranicy.app.data.isPseudoAuthor

/** Загруженные карточки FantLab, чтобы «Назад» не грузил заново. */
private object WorkCache {
    val map = HashMap<String, WorkDetails>()
}

@Composable
fun BookScreen(id: String, onBack: () -> Unit, onBook: (String) -> Unit, onAuthor: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val entry = shelf[id]
    var details by remember(id) { mutableStateOf(WorkCache.map[id]) }
    var loading by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    /** Данные из запасного источника, когда FantLab недоступен. */
    var fallback by remember(id) { mutableStateOf<Book?>(null) }

    LaunchedEffect(id) {
        if (id.startsWith("fl-") && details == null) {
            loading = true
            try {
                val d = FantLab.work(id)
                WorkCache.map[id] = d
                details = d
                // открыт отслеживаемый цикл — заодно синхронизируем его состав
                if (CycleRepository.isTracked(id)) CycleRepository.applyUpdate(d)
                CycleRepository.updateCover(id, d.book.coverUrl)
                // обновляем книгу на полке свежими данными (обложка, аннотация, цикл)
                ShelfRepository.get(id)?.let { ShelfRepository.refreshBook(d.book) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "FantLab"
                (BookCache.get(id) ?: entry?.book)?.let { fallback = GoogleBooks.lookup(it) }
            } finally {
                loading = false
            }
        }
    }

    val baseBook = details?.book ?: BookCache.get(id) ?: entry?.book
    val extra = fallback
    /** Обложка с ЛитРес, если на FantLab её нет. */
    var litresCover by remember(id) { mutableStateOf<String?>(null) }
    val book = (if (baseBook != null && extra != null) baseBook.copy(
        annotation = baseBook.annotation.ifBlank { extra.annotation },
        coverUrl = baseBook.coverUrl ?: extra.coverUrl,
    ) else baseBook)?.let { b -> if (b.coverUrl == null && litresCover != null) b.copy(coverUrl = litresCover) else b }
    val needCover = !loading && baseBook != null && !baseBook.isCycle && baseBook.coverUrl == null && extra?.coverUrl == null
    LaunchedEffect(id, needCover) {
        val b = baseBook ?: return@LaunchedEffect
        if (!needCover) return@LaunchedEffect
        val url = ru.moistranicy.app.data.Litres.cover(b.title, b.authors.firstOrNull()?.name) ?: return@LaunchedEffect
        litresCover = url
        // запоминаем найденную обложку для полки и коллажей циклов
        BookCache.put(b.copy(coverUrl = url))
        ShelfRepository.get(id)?.let { e -> if (e.book.coverUrl == null) ShelfRepository.refreshBook(e.book.copy(coverUrl = url)) }
        CycleRepository.updateCover(id, url)
    }
    val cycles by CycleRepository.cycles.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var sheetCycleId by remember(id) { mutableStateOf<String?>(null) }
    var followError by remember(id) { mutableStateOf<String?>(null) }

    /** Ближайший цикл книги на FantLab (если есть). */
    val cycleId = (details?.book?.seriesId ?: book?.seriesId)?.takeIf { it.startsWith("fl-") }

    // окно оценки после «Прочитал»; подсказка по циклу — после него
    var rateDialog by remember(id) { mutableStateOf(false) }
    // подтверждение отмены статуса
    var confirmUndo by remember(id) { mutableStateOf<ReadStatus?>(null) }
    var pendingSheet by remember(id) { mutableStateOf<String?>(null) }
    // «отметить весь цикл прочитанным»: 1 — подтверждение, 2 — вопрос про книги других авторов
    var markAllStep by remember(id) { mutableIntStateOf(0) }

    fun removeFromShelf() {
        ShelfRepository.remove(id)
        CycleRepository.onBookRemoved(id)
    }

    fun closeRating() {
        rateDialog = false
        pendingSheet?.let { sheetCycleId = it }
        pendingSheet = null
    }

    fun onStatus(b: Book, s: ReadStatus) {
        val wasRead = ShelfRepository.get(b.id)?.status == ReadStatus.READ
        ShelfRepository.setStatus(b, s)
        if (s == ReadStatus.READ && !wasRead) rateDialog = true
        val cid = cycleId ?: return
        if (s != ReadStatus.READ && s != ReadStatus.READING) return
        scope.launch {
            try {
                CycleRepository.ensureFollowed(cid)
                if (s == ReadStatus.READ) {
                    if (rateDialog) pendingSheet = cid else sheetCycleId = cid
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                followError = "Не удалось загрузить цикл с FantLab"
            }
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (entry != null) {
                        IconButton(onClick = { removeFromShelf() }) {
                            Icon(Icons.Filled.Delete, "Убрать с полки")
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (book == null) {
            Box(Modifier.padding(pad)) {
                if (loading) Loading() else CenterMessage(error?.let { "Не удалось загрузить данные: $it" } ?: "Книга не найдена")
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            if (book.isCycle) {
                // страница цикла: постер во всю ширину, как на полке, ниже — сведения
                val tc = cycles[id]
                if (tc != null) LaunchedEffect(id) { CycleRepository.ensureCovers(id) }
                val covers = tc?.let { CycleRepository.collageCovers(it) }
                    ?: details?.children.orEmpty().filter { !it.book.isCycle && it.published }
                        .mapNotNull { it.book.coverUrl ?: BookCache.get(it.book.id)?.coverUrl }.take(5)
                        .ifEmpty { listOfNotNull(book.coverUrl) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    SeriesCollagePoster(
                        title = book.title,
                        covers = covers,
                        genres = book.genres.ifEmpty { tc?.genres.orEmpty() },
                        width = maxWidth,
                        corner = 14.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val d0 = details
                    val following = tc != null
                    IconButton(
                        enabled = following || (d0 != null && d0.children.isNotEmpty()),
                        onClick = { if (following) CycleRepository.unfollow(id) else d0?.let { CycleRepository.follow(it) } },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_eye),
                            if (following) "Слежу за циклом (нажмите, чтобы перестать)" else "Следить за циклом",
                            tint = if (following) EyeBlue else EyeGrey,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                book.authors.forEach { a ->
                    val aid = a.id?.takeIf { it.startsWith("fl-") }
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (aid != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable(enabled = aid != null) { aid?.let(onAuthor) }.padding(vertical = 2.dp),
                    )
                }
                val meta = listOfNotNull(book.workType, book.year?.let { "$it г." }).joinToString(", ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    book.communityRating?.let {
                        Text("★ %.2f  FantLab".format(it), style = MaterialTheme.typography.titleSmall, color = Coral)
                        Spacer(Modifier.width(16.dp))
                    }
                    // оценка цикла — средняя по оценкам прочитанных книг
                    val ids = tc?.books?.map { it.id } ?: details?.children.orEmpty().map { it.book.id }
                    val avg = averageRating(ids, shelf)
                    if (avg != null) {
                        MiniStars(avg, size = 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "моя " + "%.1f".format(avg).replace('.', ','),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else
            Row {
                BookCover(book, Modifier.width(120.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    book.authors.forEach { a ->
                        val aid = a.id?.takeIf { it.startsWith("fl-") }
                        Text(
                            a.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (aid != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clickable(enabled = aid != null) { aid?.let(onAuthor) }
                                .padding(vertical = 2.dp),
                        )
                    }
                    val meta = listOfNotNull(book.workType, book.year?.let { "$it г." }).joinToString(", ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    book.communityRating?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "★ %.2f".format(it) + if (book.source == "fl") "  FantLab" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = Coral,
                        )
                    }
                    // поиск в Яндексе: «автор название скачать fb2» (только в сборке с GitHub)
                    if (!ru.moistranicy.app.BuildConfig.STORE_BUILD) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Найти FB2 в Яндексе →",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val q = listOfNotNull(book.authors.firstOrNull()?.name, book.title, "скачать fb2")
                                .joinToString(" ")
                            runCatching { uri.openUri("https://yandex.ru/search/?text=" + Uri.encode(q)) }
                        }.padding(vertical = 2.dp),
                    )
                    }
                }
            }

            val parents = details?.parents.orEmpty()
            if (parents.isNotEmpty()) {
                // «Входит в: Цикл / Подцикл» — каждое название открывает цикл
                Spacer(Modifier.height(12.dp))
                FlowRow(verticalArrangement = Arrangement.Center) {
                    Text("Входит в: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    parents.forEachIndexed { i, p ->
                        Text(
                            p.name + if (i < parents.size - 1) " / " else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onBook(p.id) },
                        )
                    }
                }
            } else if (book.seriesName != null) {
                Spacer(Modifier.height(12.dp))
                Text("Входит в: ${book.seriesName}", color = MaterialTheme.colorScheme.primary)
            }

            if (cycleId != null && cycles.containsKey(cycleId)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "✓ Цикл «${cycles[cycleId]?.name}» в отслеживаемых",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { sheetCycleId = cycleId },
                )
            }


            Spacer(Modifier.height(16.dp))
            Text("Статус", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ru.moistranicy.app.data.VISIBLE_STATUSES.forEach { s ->
                    FilterChip(
                        selected = entry?.status == s,
                        onClick = {
                            // повторное нажатие на выбранный статус — отменить (с подтверждением)
                            if (entry?.status == s) confirmUndo = s else onStatus(book, s)
                        },
                        label = { Text(s.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            if (entry != null) {
                Spacer(Modifier.height(12.dp))
                Text("Моя оценка", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val myRating = entry.rating ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarRating(myRating, onChange = { ShelfRepository.setRating(id, it) }, starSize = 32.dp, spacing = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    if (myRating > 0) {
                        Column {
                            Text("$myRating / 10", color = Coral, fontWeight = FontWeight.Bold)
                            Text(
                                ratingLabel(myRating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { ShelfRepository.setRating(id, null) }) {
                            Icon(Icons.Filled.Close, "Сбросить оценку", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (book.genres.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    book.genres.forEach { g -> SuggestionChip(onClick = {}, label = { Text(g) }) }
                }
            }

            if (book.annotation.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("Аннотация", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(book.annotation, style = MaterialTheme.typography.bodyMedium)
            }

            val children = details?.children.orEmpty()
            if (children.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                val readCount = children.count { shelf[it.book.id]?.status == ReadStatus.READ }
                val mainAuthor = book.authors.firstOrNull { !isPseudoAuthor(it) }?.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Состав цикла · прочитано $readCount из ${children.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // отметить все книги цикла прочитанными (с подтверждением)
                    IconButton(onClick = { markAllStep = 1 }) {
                        Icon(
                            painterResource(R.drawable.ic_star_border),
                            "Отметить все книги цикла прочитанными",
                            tint = Coral,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // отметки книг; подцикл, все книги которого другого автора, — тем же цветом
                val leafMarks = children.map { c -> if (c.book.isCycle) null else markFor(c.book, mainAuthor) }
                val marks = children.mapIndexed { i, c ->
                    if (!c.book.isCycle) leafMarks[i] else {
                        val inner = (i + 1 until children.size).takeWhile { children[it].deep > c.deep }
                            .filter { !children[it].book.isCycle }.map { leafMarks[it] }
                        when {
                            markFor(c.book, mainAuthor) == BookMark.OTHER_AUTHOR -> BookMark.OTHER_AUTHOR
                            inner.isNotEmpty() && inner.all { it == BookMark.OTHER_AUTHOR } -> BookMark.OTHER_AUTHOR
                            else -> null
                        }
                    }
                }
                children.forEachIndexed { ci, c ->
                    val st = shelf[c.book.id]?.status
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onBook(c.book.id) }
                            .padding(start = (12 * (c.deep - 1)).dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val mark = marks[ci]
                            val titleColor = mark?.color ?: MaterialTheme.colorScheme.onSurface
                            Text(
                                c.book.title,
                                style = if (c.deep == 1) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (c.book.isCycle) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (!c.book.isCycle && !c.published && st != ReadStatus.READ) titleColor.copy(alpha = 0.5f) else titleColor,
                            )
                            // ещё не вышла (и не прочитана) — подписываем «ожидается»
                            val expected = !c.book.isCycle && !c.published && st != ReadStatus.READ
                            val m = listOfNotNull(c.book.workType, c.book.year, if (expected) "ожидается" else null, mark?.label)
                                .joinToString(", ")
                            if (m.isNotEmpty()) {
                                Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!c.book.isCycle) StatusMark(st, shelf[c.book.id]?.rating)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            ExternalSourcesRow(ExternalSources.queryFor(book), "Найти книгу:")

            followError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Не удалось обновить данные: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (extra != null) {
                    Text(
                        "Описание и обложка могут быть из Google Книг",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (rateDialog && book != null) {
        var pick by remember(id) { mutableIntStateOf(shelf[id]?.rating ?: 0) }
        ModalBottomSheet(onDismissRequest = { closeRating() }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(52.dp), corner = 5.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (book.authors.isNotEmpty()) {
                            Text(book.authorsLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                        Text("Вы прочитали книгу 🎉", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                StarRating(pick, onChange = { pick = it }, starSize = 46.dp, spacing = 6.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
                    if (pick > 0) {
                        Text("$pick", color = Coral, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text(" / 10 · ${ratingLabel(pick)}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    } else {
                        Text("Нажмите на звёзды — каждая половинка это балл", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { closeRating() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Позже") }
                    Box(
                        Modifier
                            .weight(2f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (pick > 0) Brush.horizontalGradient(listOf(Violet, VioletDeep))
                                else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)),
                            )
                            .clickable(enabled = pick > 0) {
                                ShelfRepository.setRating(id, pick)
                                closeRating()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Сохранить",
                            color = if (pick > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    val undo = confirmUndo
    if (undo != null && book != null) {
        val r = shelf[id]?.rating
        ModalBottomSheet(onDismissRequest = { confirmUndo = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(44.dp), corner = 5.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Отменить статус «${undo.title}»?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "«${book.title}» уйдёт с полки" + (if (r != null) ", оценка $r / 10 будет удалена." else "."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { confirmUndo = null },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Оставить") }
                    Box(
                        Modifier
                            .weight(1.4f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(Coral, Color(0xFFB8401F))))
                            .clickable {
                                removeFromShelf()
                                confirmUndo = null
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("Отменить статус", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (markAllStep > 0 && book != null) {
        val d = details
        val mainAuthor = book.authors.firstOrNull { !isPseudoAuthor(it) }?.id
        // книги цикла (без подциклов и мелких форм), вышедшие и ещё не прочитанные
        val unread = d?.let { cycleBooks(it.children) }.orEmpty()
            .filter { it.published && shelf[it.book.id]?.status != ReadStatus.READ }
        val side = unread.filter { markFor(it.book, mainAuthor) == BookMark.OTHER_AUTHOR }
        val main = unread - side.toSet()
        fun markRead(items: List<ru.moistranicy.app.data.CycleItem>) {
            if (d != null && !CycleRepository.isTracked(id)) CycleRepository.follow(d)
            val tc = CycleRepository.get(id)
            items.forEach { item ->
                val cb = tc?.books?.firstOrNull { b -> b.id == item.book.id }
                val b = if (tc != null && cb != null) CycleRepository.bookOf(tc, cb)
                else item.book.copy(seriesId = item.book.seriesId ?: id, seriesName = item.book.seriesName ?: book.title)
                ShelfRepository.setStatus(b, ReadStatus.READ, knownDate = false)
            }
        }
        if (unread.isEmpty()) {
            AlertDialog(
                onDismissRequest = { markAllStep = 0 },
                confirmButton = { TextButton(onClick = { markAllStep = 0 }) { Text("Понятно") } },
                title = { Text("Всё уже прочитано") },
                text = { Text("Все вышедшие книги цикла «${book.title}» уже отмечены прочитанными.") },
            )
        } else if (markAllStep == 1) {
            AlertDialog(
                onDismissRequest = { markAllStep = 0 },
                title = { Text("Отметить весь цикл?") },
                text = {
                    Text(
                        "Вы уверены, что хотите отметить все книги цикла «${book.title}» прочитанными? " +
                            "Книг: ${unread.size}. Оценки и даты прочтения ставить не будем.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (side.isEmpty()) { markRead(main); markAllStep = 0 } else markAllStep = 2
                    }) { Text("Отметить") }
                },
                dismissButton = { TextButton(onClick = { markAllStep = 0 }) { Text("Отмена") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { markAllStep = 0 },
                title = { Text("Книги других авторов") },
                text = {
                    Text(
                        "В цикле есть книги других авторов (${side.size}): " +
                            side.take(3).joinToString(", ") { "«${it.book.title}»" } + (if (side.size > 3) "…" else "") +
                            ". Отметить их тоже прочитанными?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { markRead(unread); markAllStep = 0 }) { Text("Да, все") }
                },
                dismissButton = {
                    TextButton(onClick = { markRead(main); markAllStep = 0 }) { Text("Только основного автора") }
                },
            )
        }
    }

    val sheetId = sheetCycleId
    val sheetCycle = sheetId?.let { cycles[it] }
    if (sheetCycle != null) {
        ModalBottomSheet(onDismissRequest = { sheetCycleId = null }) {
            val rest = sheetCycle.books.withIndex()
                .filter { (_, b) -> !isHiddenType(b.type) && shelf[b.id]?.status != ReadStatus.READ && !(b.id == id && shelf[id] != null) }
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Цикл «${sheetCycle.name}»", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val required = sheetCycle.books.filter { it.published && !it.optional && !isHiddenType(it.type) }
                val total = required.size
                val read = required.count { shelf[it.id]?.status == ReadStatus.READ }
                Text(
                    "Прочитано $read из $total. Остальные книги цикла — в «Полка → Хочу прочитать», " +
                        "о новых книгах придёт уведомление.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // книгу отметили прочитанной, а предыдущие — нет: предлагаем отметить их разом, без оценок
                val position = sheetCycle.books.indexOfFirst { it.id == id }
                val previous = if (position > 0 && shelf[id]?.status == ReadStatus.READ) {
                    sheetCycle.books.take(position).filter { b ->
                        val st = shelf[b.id]?.status
                        b.published && !b.optional && !isHiddenType(b.type) && st != ReadStatus.READ && st != ReadStatus.DROPPED
                    }
                } else emptyList()
                if (previous.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            previous.forEach { b ->
                                ShelfRepository.setStatus(CycleRepository.bookOf(sheetCycle, b), ReadStatus.READ, knownDate = false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отметить предыдущие книги прочитанными (${previous.size})") }
                    Text(
                        "Оценки ставить не будем.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (rest.isEmpty() || (total > 0 && read == total && rest.all { it.value.optional || !it.value.published })) {
                    if (total > 0 && read == total) {
                        Text("Вы прочитали весь цикл! 🎉", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("Что читать дальше", style = MaterialTheme.typography.titleSmall)
                    val next = rest.firstOrNull { (_, b) -> b.published && !b.optional && shelf[b.id] == null }
                    if (next != null) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            ShelfRepository.setStatus(CycleRepository.bookOf(sheetCycle, next.value), ReadStatus.WANT)
                        }) { Text("«${next.value.title}» — хочу прочитать", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rest.forEach { (i, b) ->
                            CompactBookLine(
                                number = i + 1,
                                title = b.title,
                                sub = listOfNotNull(b.year, if (!b.published) "ожидается" else null).joinToString(", "),
                                status = shelf[b.id]?.status,
                                isNew = b.id in sheetCycle.newIds,
                                published = b.published,
                                indent = 0,
                                mark = b.mark(),
                                statusIcon = true,
                                rating = shelf[b.id]?.rating,
                            ) {
                                sheetCycleId = null
                                onBook(b.id)
                            }
                        }
                    }
                }
            }
        }
    }
}
