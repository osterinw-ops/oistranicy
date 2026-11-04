@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.CycleBook
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfEntry
import ru.moistranicy.app.data.ShelfRepository
import ru.moistranicy.app.data.TrackedCycle

/**
 * Аналог «Непросмотренного» в MyShows: по каждому отслеживаемому циклу —
 * только непрочитанные книги по порядку. Прочитанные исчезают.
 */
@Composable
fun CyclesScreen(onBook: (String) -> Unit) {
    val cycles by CycleRepository.cycles.collectAsState()
    val shelf by ShelfRepository.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var showFinished by remember { mutableStateOf(false) }

    // «новые» подсвечиваются во время этого визита, при уходе с экрана — считаются просмотренными
    DisposableEffect(Unit) { onDispose { CycleRepository.markAllSeen() } }

    fun isRead(id: String) = shelf[id]?.status == ReadStatus.READ

    val all = cycles.values.toList()
    val active = all.filter { c -> c.books.any { it.published && !isRead(it.id) } || c.books.any { !it.published } }
        .sortedWith(
            compareByDescending<TrackedCycle> { it.newIds.isNotEmpty() }
                .thenByDescending { c -> c.books.any { shelf[it.id]?.status == ReadStatus.READING } }
                .thenBy { it.name },
        )
    val finished = all.filter { it !in active }.sortedBy { it.name }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Циклы") },
                actions = {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else if (all.isNotEmpty()) {
                        IconButton(onClick = {
                            checking = true
                            scope.launch {
                                val news = runCatching { CycleRepository.checkAll() }.getOrDefault(emptyList())
                                checkResult = if (news.isEmpty()) "Новых книг нет" else "Новых книг: ${news.size}"
                                checking = false
                            }
                        }) { Icon(Icons.Filled.Refresh, "Проверить новинки") }
                    }
                },
            )
        },
    ) { pad ->
        if (all.isEmpty()) {
            Box(Modifier.padding(pad)) {
                CenterMessage(
                    "Здесь появятся циклы, которые вы читаете.\n\n" +
                        "Отметьте книгу из цикла как «Читаю» или «Прочитал» — цикл добавится сам, " +
                        "а когда выйдет новая книга, придёт уведомление.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            checkResult?.let { msg ->
                item {
                    Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(active, key = { it.id }) { c ->
                CycleCard(c, shelf, onBook)
            }
            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }) {
                        Text((if (showFinished) "Скрыть" else "Показать") + " прочитанные циклы (${finished.size})")
                    }
                }
                if (showFinished) {
                    items(finished, key = { "f" + it.id }) { c -> CycleCard(c, shelf, onBook) }
                }
            }
        }
    }
}

@Composable
private fun CycleCard(c: TrackedCycle, shelf: Map<String, ShelfEntry>, onBook: (String) -> Unit) {
    val published = c.books.filter { it.published }
    val readCount = published.count { shelf[it.id]?.status == ReadStatus.READ }
    val unread = c.books.withIndex().filter { (_, b) -> shelf[b.id]?.status != ReadStatus.READ }
    var confirmUnfollow by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onBook(c.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (c.newIds.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        NewBadge()
                    }
                }
                if (c.authors.isNotEmpty()) {
                    Text(
                        c.authors.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (confirmUnfollow) {
                TextButton(onClick = { CycleRepository.unfollow(c.id) }) { Text("Не следить") }
            } else {
                IconButton(onClick = { confirmUnfollow = true }) {
                    Icon(Icons.Filled.Close, "Перестать следить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Прочитано $readCount из ${published.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (published.isEmpty()) 0f else readCount.toFloat() / published.size },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(6.dp))
        if (unread.isEmpty()) {
            Text("Цикл прочитан полностью 🎉", style = MaterialTheme.typography.bodyMedium)
        }
        unread.forEach { (i, b) ->
            CycleBookRow(c, i + 1, b, shelf[b.id], isNew = b.id in c.newIds, onBook = onBook)
        }
    }
}

@Composable
fun CycleBookRow(
    c: TrackedCycle,
    number: Int,
    b: CycleBook,
    entry: ShelfEntry?,
    isNew: Boolean,
    onBook: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onBook(b.id) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$number.",
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                b.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (b.published) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val meta = listOfNotNull(
                b.year,
                if (!b.published) "ожидается" else null,
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry != null) {
                    Spacer(Modifier.width(6.dp))
                    StatusBadge(entry.status)
                }
                if (isNew) {
                    Spacer(Modifier.width(6.dp))
                    NewBadge()
                }
            }
        }
        if (b.published) {
            // быстрая отметка «прочитал» — книга сразу уходит из списка
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable { ShelfRepository.setStatus(CycleRepository.bookOf(c, b), ReadStatus.READ) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, "Прочитал", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun NewBadge() {
    Text(
        "НОВОЕ",
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Coral)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
