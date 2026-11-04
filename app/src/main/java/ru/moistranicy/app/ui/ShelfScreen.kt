@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.clickable
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

/** Выбранная вкладка полки — переживает переходы и открытие из уведомления. */
object ShelfUi {
    var tab by mutableIntStateOf(0)
    /** Раскрытые циклы (по умолчанию все свёрнуты). */
    val expanded = mutableStateMapOf<String, Boolean>()
}

private data class Line(
    val id: String,
    val number: Int?,
    val title: String,
    val sub: String?,
    val isNew: Boolean,
    val published: Boolean,
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
        val published = c.books.filter { it.published }
        val read = published.count { shelf[it.id]?.status == ReadStatus.READ }
        val lines = c.books.withIndex().filter { (_, b) ->
            val st = shelf[b.id]?.status
            if (status == ReadStatus.WANT) st == ReadStatus.WANT || st == null else st == status
        }.map { (i, b) ->
            used += b.id
            Line(b.id, i + 1, b.title, b.year?.let { if (b.published) it else "$it, ожидается" }
                ?: if (!b.published) "ожидается" else null, b.id in c.newIds, b.published)
        }
        if (lines.isNotEmpty()) {
            cycleGroups += Group(
                key = c.id, title = c.name, cycleId = c.id, progress = "$read из ${published.size}",
                lines = lines, hasNew = c.newIds.isNotEmpty(), isSeries = true,
                covers = CycleRepository.collageCovers(c),
                author = c.authors.joinToString(", ") { it.name }.ifEmpty { null },
                unread = published.size - read, total = published.size, tracked = true,
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
        )
    }.sortedBy { it.title }
    val singles = rest.filter { it.book.seriesName == null }
    val result = cycleGroups + seriesGroups
    return if (singles.isEmpty()) result else result + Group(
        key = "single",
        title = if (result.isEmpty()) null else "Вне циклов",
        cycleId = null,
        progress = null,
        lines = singles.map { e ->
            Line(e.book.id, null, e.book.title, listOfNotNull(e.book.authorsLine.ifEmpty { null }, e.book.year).joinToString(", "), false, true)
        },
        hasNew = false,
    )
}

@Composable
fun ShelfScreen(onBook: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val cycles by CycleRepository.cycles.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    // метки «новая» видны во время визита, при уходе с полки считаются просмотренными
    DisposableEffect(Unit) { onDispose { CycleRepository.markAllSeen() } }

    val statuses = ReadStatus.entries
    val groupsByStatus = statuses.associateWith { buildGroups(it, shelf, cycles) }
    val current = statuses[ShelfUi.tab.coerceIn(0, statuses.size - 1)]
    val groups = groupsByStatus.getValue(current)

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Моя полка") },
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
            ScrollableTabRow(selectedTabIndex = ShelfUi.tab, edgePadding = 12.dp) {
                statuses.forEachIndexed { i, s ->
                    val count = groupsByStatus.getValue(s).sumOf { it.lines.size }
                    Tab(
                        selected = ShelfUi.tab == i,
                        onClick = { ShelfUi.tab = i },
                        text = { Text("${s.title} $count", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            if (groups.isEmpty()) {
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
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
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
                        if (open) {
                            items(g.lines, key = { g.key + "/" + it.id }) { l ->
                                CompactBookLine(
                                    number = l.number,
                                    title = l.title,
                                    sub = l.sub,
                                    isNew = l.isNew,
                                    published = l.published,
                                    indent = if (g.isSeries) 84 else 16,
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
        SeriesCollage(
            g.covers,
            g.title.orEmpty(),
            Modifier.then(if (cid != null) Modifier.clickable { onBook(cid) } else Modifier),
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
