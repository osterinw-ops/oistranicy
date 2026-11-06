@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.ShelfRepository
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfEntry
import ru.moistranicy.app.data.TrackedCycle
import ru.moistranicy.app.data.isHiddenType

/** Выбранная вкладка полки — переживает переходы и открытие из уведомления. */
object ShelfUi {
    var tab by mutableIntStateOf(0)
    /** Раскрытые циклы (по умолчанию все свёрнуты). */
    val expanded = mutableStateMapOf<String, Boolean>()

    /** Повторное нажатие на «Полку»: вкладка «Читаю», всё свёрнуто. */
    fun reset() {
        tab = 0
        expanded.clear()
    }
}

private data class Line(
    val id: String,
    val number: Int?,
    val title: String,
    val sub: String?,
    val isNew: Boolean,
    val published: Boolean,
    val mark: BookMark? = null,
)

private data class Group(
    val key: String,
    val title: String?,
    val cycleId: String?,
    val progress: String?,
    val lines: List<Line>,
    val hasNew: Boolean,
    /** Сворачиваемая серия с обложкой (для «Вне циклов» — false). */
    val isSeries: Boolean = false,
    val covers: List<String> = emptyList(),
    val author: String? = null,
    val unread: Int? = null,
    val total: Int? = null,
    val tracked: Boolean = false,
    val genres: List<String> = emptyList(),
    val dropped: Boolean = false,
    val posterUrl: String? = null,
    val bookCount: Int? = null,
    /** Отдельные книги (без цикла). */
    val singles: Boolean = false,
    /** Корешки для постера цикла. */
    val spines: List<Spine> = emptyList(),
    /** Группа «автор» (во вкладке «Прочитал» — книги вне циклов по авторам). */
    val isAuthor: Boolean = false,
    val authorPhoto: String? = null,
    /** id автора (FantLab) — для перехода на страницу автора и группировки. */
    val authorId: String? = null,
    /** Для группы-автора: его прочитанные серии. */
    val children: List<Group> = emptyList(),
    /** Для группы-автора: всего прочитано книг. */
    val readBooks: Int = 0,
)

/**
 * Книги статуса, сгруппированные по циклам.
 * В «Хочу прочитать» дополнительно попадают все непрочитанные книги отслеживаемых циклов.
 */
private fun buildGroups(
    status: ReadStatus,
    shelf: Map<String, ShelfEntry>,
    cycles: Map<String, TrackedCycle>,
): List<Group> {
    val used = HashSet<String>()
    val cycleGroups = mutableListOf<Group>()
    for (c in cycles.values) {
        // скрытые типы (статьи, сборники…) не показываем вовсе
        val books = c.books.withIndex().filter { !isHiddenType(it.value.type) }
        // обязательные книги: вышли, написаны основным автором и изданы на русском
        val published = books.map { it.value }.filter { it.published && !it.optional }
        val read = published.count { shelf[it.id]?.status == ReadStatus.READ }
        val fullyRead = published.isNotEmpty() && read == published.size
        // книги отслеживаемого цикла не попадают в «Вне циклов» ни в одной вкладке
        c.books.forEach { used += it.id }
        // «Прочитал» — все прочитанные книги цикла, даже если цикл не дочитан;
        // прочитанный целиком цикл в «Хочу прочитать» не показываем, пока не выйдет новая книга
        if (status == ReadStatus.WANT && fullyRead) continue
        // ни одна книга цикла ещё не вышла — предлагать нечего (кроме явно отмеченных)
        if (status == ReadStatus.WANT && published.isEmpty() &&
            c.books.none { shelf[it.id]?.status == ReadStatus.WANT }
        ) continue
        val lines = books.filter { (_, b) ->
            val st = shelf[b.id]?.status
            // брошенный цикл больше не предлагаем: в «Хочу» — только явно отмеченные
            if (status == ReadStatus.WANT && !c.dropped) st == ReadStatus.WANT || st == null else st == status
        }.map { (i, b) ->
            used += b.id
            Line(b.id, i + 1, b.title, b.year?.let { if (b.published) it else "$it, ожидается" }
                ?: if (!b.published) "ожидается" else null, b.id in c.newIds, b.published, b.mark())
        }
        if (lines.isNotEmpty()) {
            cycleGroups += Group(
                key = c.id, title = c.name, cycleId = c.id, progress = "$read из ${published.size}",
                lines = lines, hasNew = c.newIds.isNotEmpty(), isSeries = true,
                covers = CycleRepository.collageCovers(c),
                author = c.authors.joinToString(", ") { it.name }.ifEmpty { null },
                unread = published.size - read, total = published.size, tracked = true,
                genres = c.genres, dropped = c.dropped,
                // своя картинка цикла на FantLab (обложка сборника), иначе — обложка первой книги
                posterUrl = c.coverUrl?.takeIf { it.contains("/images/editions/") }
                    ?: c.books.firstOrNull { it.published && it.coverUrl != null }?.coverUrl,
                bookCount = published.size,
                spines = books.map { (_, b) -> Spine(b.id, shelf[b.id]?.status, b.published, b.id in c.newIds) },
                authorId = c.authors.firstOrNull()?.id,
            )
        }
    }
    cycleGroups.sortWith(compareByDescending<Group> { it.hasNew }.thenBy { it.title })

    // книги с этим статусом, чей цикл не отслеживается или которые вне циклов
    val rest = shelf.values.filter { it.status == status && it.book.id !in used }
        .sortedByDescending { it.updatedAt }
    val bySeries = rest.filter { it.book.seriesName != null }.groupBy { it.book.seriesId ?: it.book.seriesName!! }
    val seriesGroups = bySeries.values.map { list ->
        val b0 = list.first().book
        Group(
            key = "s:" + (b0.seriesId ?: b0.seriesName),
            title = b0.seriesName,
            cycleId = b0.seriesId?.takeIf { it.startsWith("fl-") },
            progress = null,
            lines = list.map { e -> Line(e.book.id, e.book.seriesNumber, e.book.title, e.book.year, false, true) },
            hasNew = false,
            isSeries = true,
            covers = list.mapNotNull { it.book.coverUrl }.take(3),
            author = b0.authorsLine.ifEmpty { null },
            genres = list.flatMap { it.book.genres }.distinct(),
            posterUrl = list.firstNotNullOfOrNull { it.book.coverUrl },
            spines = list.sortedBy { it.book.seriesNumber ?: Int.MAX_VALUE }
                .map { e -> Spine(e.book.id, e.status, true, false) },
            authorId = b0.authors.firstOrNull()?.id,
        )
    }.sortedBy { it.title }
    val singles = rest.filter { it.book.seriesName == null }
    val result = cycleGroups + seriesGroups
    if (status == ReadStatus.READ) {
        // «Прочитал»: всё — и прочитанные серии, и отдельные книги — по авторам
        fun keyOf(id: String?, name: String?) =
            id?.takeIf { it.startsWith("fl-") } ?: ("name:" + (name ?: "Автор не указан").substringBefore(", "))
        val seriesByAuthor = result.groupBy { g -> keyOf(g.authorId, g.author) }
        val singlesByAuthor = singles.groupBy { e ->
            val a = e.book.authors.firstOrNull()
            keyOf(a?.id, a?.name)
        }
        val keys = (seriesByAuthor.keys + singlesByAuthor.keys)
        val authorGroups = keys.map { key ->
            val series = seriesByAuthor[key].orEmpty().sortedBy { it.title }
            val books = singlesByAuthor[key].orEmpty().sortedByDescending { it.finishedAt ?: it.updatedAt }
            val firstAuthor = books.firstOrNull()?.book?.authors?.firstOrNull()
            val name = firstAuthor?.name
                ?: series.firstOrNull()?.author?.substringBefore(", ")
                ?: "Автор не указан"
            val id = key.takeIf { it.startsWith("fl-") }
            Group(
                key = "a:$key",
                title = name,
                cycleId = null,
                progress = null,
                lines = books.map { e -> Line(e.book.id, null, e.book.title, e.book.year, false, true) },
                hasNew = false,
                isAuthor = true,
                authorId = id,
                authorPhoto = id?.let { "https://fantlab.ru/images/autors/${it.removePrefix("fl-")}" },
                children = series,
                readBooks = books.size + series.sumOf { it.lines.size },
            )
        }.sortedBy { surnameKey(surnameFirst(it.title.orEmpty())) }
        return authorGroups
    }
    return if (singles.isEmpty()) result else result + Group(
        key = "single",
        title = if (result.isEmpty()) null else "Вне циклов",
        cycleId = null,
        progress = null,
        lines = singles.map { e ->
            Line(e.book.id, null, e.book.title, listOfNotNull(e.book.authorsLine.ifEmpty { null }, e.book.year).joinToString(", "), false, true)
        },
        hasNew = false,
        singles = true,
    )
}

/** «Михаил Атаманов» → «Атаманов Михаил» (фамилия — последнее слово). */
private fun surnameFirst(name: String): String {
    val words = name.trim().split(Regex("\\s+"))
    if (words.size < 2) return name.trim()
    return words.last() + " " + words.dropLast(1).joinToString(" ")
}

/** Ключ сортировки по фамилии (ё = е, без учёта регистра). */
private fun surnameKey(surnameFirstName: String): String =
    surnameFirstName.lowercase().replace('ё', 'е')

@Composable
fun ShelfScreen(onBook: (String) -> Unit, onAuthor: (String) -> Unit = {}) {
    val shelf by ShelfRepository.entries.collectAsState()
    val cycles by CycleRepository.cycles.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    // метки «новая» видны во время визита, при уходе с полки считаются просмотренными
    DisposableEffect(Unit) { onDispose { CycleRepository.markAllSeen() } }

    val listState = rememberLazyListState()
    val resetNonce = TabReset.nonces["shelf"] ?: 0
    LaunchedEffect(resetNonce, ShelfUi.tab) { runCatching { listState.scrollToItem(0) } }

    // «Читаю» — отдельными книгами, без группировки по циклам
    val reading = shelf.values.filter { it.status == ReadStatus.READING }.sortedByDescending { it.updatedAt }
    // вкладка «Читаю» видна, только если есть книги с этим статусом
    val statuses = ReadStatus.entries.filter { it != ReadStatus.READING || reading.isNotEmpty() }
    val groupsByStatus = statuses.associateWith { buildGroups(it, shelf, cycles) }
    val selected = ReadStatus.entries.getOrElse(ShelfUi.tab) { ReadStatus.WANT }
    val current = if (selected in statuses) selected else ReadStatus.WANT
    val tabIndex = statuses.indexOf(current).coerceAtLeast(0)
    val groups = groupsByStatus.getValue(current)

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                "Моя полка",
                actions = {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                    } else if (cycles.isNotEmpty()) {
                        IconButton(onClick = {
                            checking = true
                            scope.launch {
                                val news = runCatching { CycleRepository.checkAll() }.getOrDefault(emptyList())
                                checkResult = if (news.isEmpty()) "Новых книг в циклах нет" else "Новых книг в циклах: ${news.size}"
                                checking = false
                            }
                        }) { Icon(Icons.Filled.Refresh, "Проверить новинки в циклах") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 12.dp) {
                statuses.forEachIndexed { i, s ->
                    val count = if (s == ReadStatus.READING) reading.size
                    else groupsByStatus.getValue(s).sumOf { if (it.isAuthor) it.readBooks else it.lines.size }
                    Tab(
                        selected = tabIndex == i,
                        onClick = { ShelfUi.tab = s.ordinal },
                        text = { Text("${s.title} $count", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            if (current == ReadStatus.READING && reading.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(reading, key = { it.book.id }) { e -> ReadingRow(e, cycles) { onBook(e.book.id) } }
                }
            } else if (groups.isEmpty() || current == ReadStatus.READING) {
                CenterMessage(
                    when (current) {
                        ReadStatus.READING -> "Сейчас ничего не читаете.\nНайдите книгу во вкладке «Поиск»."
                        ReadStatus.WANT -> "Здесь будут книги, которые хотите прочитать, " +
                            "и следующие книги циклов, которые вы читаете."
                        ReadStatus.READ -> "Здесь появятся прочитанные книги."
                        ReadStatus.DROPPED -> "Брошенных книг нет."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    checkResult?.let { msg ->
                        item {
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    groups.forEach { g ->
                        val open = !g.isSeries || ShelfUi.expanded[g.key] == true
                        if (g.isAuthor) {
                            val openA = ShelfUi.expanded[g.key] == true
                            item(key = "h" + g.key) {
                                AuthorGroupHeader(g, openA, onAuthor = onAuthor) { ShelfUi.expanded[g.key] = !openA }
                            }
                            if (openA) {
                                g.children.forEach { c ->
                                    val openC = ShelfUi.expanded[c.key] == true
                                    item(key = g.key + "/h/" + c.key) {
                                        Box(Modifier.padding(start = 12.dp)) {
                                            SeriesHeader(c, openC, onToggle = { ShelfUi.expanded[c.key] = !openC }, onBook = onBook)
                                        }
                                    }
                                    if (openC) {
                                        items(c.lines, key = { g.key + "/" + c.key + "/" + it.id }) { l ->
                                            CompactBookLine(
                                                number = l.number,
                                                title = l.title,
                                                sub = l.sub,
                                                published = l.published,
                                                mark = l.mark,
                                                indent = 40,
                                            ) { onBook(l.id) }
                                        }
                                    }
                                }
                                items(g.lines, key = { g.key + "/" + it.id }) { l ->
                                    val e = shelf[l.id]
                                    if (e != null) Box(Modifier.padding(start = 12.dp)) { ReadingRow(e, cycles) { onBook(l.id) } }
                                }
                            }
                            return@forEach
                        }
                        if (g.isSeries) {
                            item(key = "h" + g.key) {
                                SeriesHeader(g, open, onToggle = { ShelfUi.expanded[g.key] = !open }, onBook = onBook)
                            }
                        } else if (g.title != null) {
                            item(key = "h" + g.key) {
                                Text(
                                    g.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                                )
                            }
                        }
                        if (g.singles) {
                            items(g.lines, key = { g.key + "/" + it.id }) { l ->
                                val e = shelf[l.id]
                                if (e != null) ReadingRow(e, cycles) { onBook(l.id) }
                            }
                        } else if (open) {
                            items(g.lines, key = { g.key + "/" + it.id }) { l ->
                                CompactBookLine(
                                    number = l.number,
                                    title = l.title,
                                    sub = l.sub,
                                    isNew = l.isNew,
                                    published = l.published,
                                    mark = l.mark,
                                    indent = if (g.isSeries) 28 else 16,
                                ) { onBook(l.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(g: Group, open: Boolean, onToggle: () -> Unit, onBook: (String) -> Unit) {
    val cid = g.cycleId
    if (g.tracked && cid != null) {
        // догружаем обложки книг серии для коллажа
        LaunchedEffect(cid) { CycleRepository.ensureCovers(cid) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesCollagePoster(
            title = g.title.orEmpty(),
            covers = g.covers,
            genres = g.genres,
            caption = g.total?.let { t -> "${t - (g.unread ?: 0)} из $t" },
            modifier = Modifier.then(if (cid != null) Modifier.clickable { onBook(cid) } else Modifier),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                g.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            g.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val unread = g.unread
            val info = when {
                g.dropped -> "цикл брошен · не прочитано: ${unread ?: 0}"
                unread == 0 -> "прочитан полностью"
                unread == null -> "книг на полке: ${g.lines.size}"
                unread == 0 -> "всё прочитано"
                else -> "не прочитано: $unread из ${g.total}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread != null && unread > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (g.hasNew) {
                    Text("  · новая книга", style = MaterialTheme.typography.labelSmall, color = Coral)
                }
            }
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Свернуть" else "Развернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Книга во вкладке «Читаю»: обложка, название, затем цикл и автор. */
@Composable
private fun ReadingRow(e: ShelfEntry, cycles: Map<String, TrackedCycle>, onClick: () -> Unit) {
    val b = e.book
    // номер и название цикла: сначала из отслеживаемых циклов, иначе из данных книги
    val tracked = cycles.values.firstOrNull { c -> c.books.any { it.id == b.id } }
    val seriesLine = when {
        tracked != null -> {
            val visible = tracked.books.filter { !isHiddenType(it.type) || it.id == b.id }
            val n = visible.indexOfFirst { it.id == b.id } + 1
            val total = visible.count { it.published }
            "Цикл «${tracked.name}», книга $n из ${maxOf(total, n)}"
        }
        b.seriesName != null -> "Цикл «${b.seriesName}»" + (b.seriesNumber?.let { ", книга $it" } ?: "")
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b, Modifier.width(60.dp), corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (seriesLine != null) {
                Text(
                    seriesLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (b.authors.isNotEmpty()) {
                Text(
                    b.authorsLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Заголовок автора во вкладке «Прочитал»: фото, имя, число книг, стрелка «раскрыть». */
@Composable
private fun AuthorGroupHeader(g: Group, open: Boolean, onAuthor: (String) -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val aid = g.authorId
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Violet, VioletDeep)))
                .then(if (aid != null) Modifier.clickable { onAuthor(aid) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val initials = g.title.orEmpty().split(' ').filter { it.isNotEmpty() }.take(2)
                .joinToString("") { it.take(1).uppercase() }
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
            g.authorPhoto?.let {
                AsyncImage(model = it, contentDescription = g.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                g.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "прочитано книг: ${g.readBooks}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (g.children.isNotEmpty()) {
                Text(
                    "прочитано серий: ${g.children.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (open) "Свернуть" else "Раскрыть",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
