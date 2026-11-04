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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.BookCache
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import ru.moistranicy.app.data.WorkDetails

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
                error = e.message ?: "ошибка сети"
            } finally {
                loading = false
            }
        }
    }

    val book = details?.book ?: BookCache.get(id) ?: entry?.book
    val cycles by CycleRepository.cycles.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var sheetCycleId by remember(id) { mutableStateOf<String?>(null) }
    var followError by remember(id) { mutableStateOf<String?>(null) }

    /** Ближайший цикл книги на FantLab (если есть). */
    val cycleId = (details?.book?.seriesId ?: book?.seriesId)?.takeIf { it.startsWith("fl-") }

    fun onStatus(b: Book, s: ReadStatus) {
        ShelfRepository.setStatus(b, s)
        val cid = cycleId ?: return
        if (s != ReadStatus.READ && s != ReadStatus.READING) return
        scope.launch {
            try {
                CycleRepository.ensureFollowed(cid)
                if (s == ReadStatus.READ) sheetCycleId = cid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                followError = "Не удалось загрузить цикл: ${e.message}"
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
                        IconButton(onClick = { ShelfRepository.remove(id) }) {
                            Icon(Icons.Filled.Delete, "Убрать с полки")
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (book == null) {
            Box(Modifier.padding(pad)) {
                if (loading) Loading() else CenterMessage(error?.let { "Не удалось загрузить: $it" } ?: "Книга не найдена")
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
                }
            }

            val parents = details?.parents.orEmpty()
            if (parents.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Входит в", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    parents.forEach { p ->
                        AssistChip(onClick = { onBook(p.id) }, label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            } else if (book.seriesName != null) {
                Spacer(Modifier.height(12.dp))
                Text("Цикл: «${book.seriesName}»", color = MaterialTheme.colorScheme.primary)
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

            val d = details
            if (book.isCycle && d != null && d.children.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                if (cycles.containsKey(id)) {
                    OutlinedButton(onClick = { CycleRepository.unfollow(id) }) { Text("✓ Слежу за циклом") }
                } else {
                    Button(onClick = { CycleRepository.follow(d) }) { Text("Следить за циклом") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Статус", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadStatus.entries.forEach { s ->
                    FilterChip(
                        selected = entry?.status == s,
                        onClick = { onStatus(book, s) },
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val myRating = entry.rating
                    (1..10).forEach { n ->
                        val selected = myRating != null && n <= myRating
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (selected) Coral else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { ShelfRepository.setRating(id, if (myRating == n) null else n) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                Text(
                    "Состав цикла · прочитано $readCount из ${children.size}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                children.forEach { c ->
                    val st = shelf[c.book.id]?.status
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onBook(c.book.id) }
                            .padding(start = (12 * (c.deep - 1)).dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.book.title,
                                style = if (c.deep == 1) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (c.book.isCycle) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val m = listOfNotNull(c.book.workType, c.book.year).joinToString(", ")
                            if (m.isNotEmpty()) {
                                Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (st != null) StatusBadge(st)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                val q = (book.title + " " + (book.authors.firstOrNull()?.name ?: "")).trim()
                uri.openUri("https://www.livelib.ru/find/books/" + Uri.encode(q))
            }) { Text("Найти на LiveLib →") }

            followError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Не удалось обновить данные: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val sheetId = sheetCycleId
    val sheetCycle = sheetId?.let { cycles[it] }
    if (sheetCycle != null) {
        ModalBottomSheet(onDismissRequest = { sheetCycleId = null }) {
            val rest = sheetCycle.books.withIndex()
                .filter { (_, b) -> b.id != id && shelf[b.id]?.status != ReadStatus.READ }
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Цикл «${sheetCycle.name}»", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val total = sheetCycle.books.count { it.published }
                val read = sheetCycle.books.count { it.published && shelf[it.id]?.status == ReadStatus.READ }
                Text(
                    "Прочитано $read из $total. Остальные книги цикла — в «Полка → Хочу прочитать», " +
                        "о новых книгах придёт уведомление.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (rest.isEmpty()) {
                    Text("Вы прочитали весь цикл! 🎉", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Что читать дальше", style = MaterialTheme.typography.titleSmall)
                    val next = rest.firstOrNull { (_, b) -> b.published && shelf[b.id] == null }
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
