@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.isHiddenType
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import ru.moistranicy.app.data.AuthorDetails
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.LangCache
import ru.moistranicy.app.data.isForeignTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private object AuthorCache {
    val map = HashMap<String, AuthorDetails>()
}

@Composable
fun AuthorScreen(id: String, onBack: () -> Unit, onBook: (String) -> Unit) {
    var details by remember(id) { mutableStateOf(AuthorCache.map[id]) }
    var error by remember(id) { mutableStateOf<Throwable?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }

    LaunchedEffect(id, attempt) {
        if (details != null) return@LaunchedEffect
        error = null
        try {
            val d = try {
                FantLab.author(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // у автора нет своей страницы на FantLab — собираем её по его книгам
                val name = ru.moistranicy.app.data.KnownAuthors.snapshot().entries.firstOrNull { it.value == id }?.key
                    ?: ShelfRepository.entries.value.values.flatMap { it.book.authors }.firstOrNull { it.id == id }?.name
                    ?: throw e
                runCatching { FantLab.authorFromWorks(id, name) }.getOrElse { throw e }
            }
            AuthorCache.map[id] = d
            details = d
            // составные романы группируем уже после показа: нужны дополнительные запросы
            val grouped = FantLab.groupNovelParts(d)
            AuthorCache.map[id] = grouped
            details = grouped
            // романы, которые на самом деле входят в цикл, — переносим в цикл
            val attached = runCatching { FantLab.attachToCycles(grouped) }.getOrNull()
            if (attached != null && attached != grouped) {
                AuthorCache.map[id] = attached
                details = attached
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text(details?.name ?: "Автор", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val d = details
            val err = error
            when {
                d != null -> AuthorContent(d, onBook)
                err != null -> CenterMessage(errorText(err), "Повторить") { attempt++ }
                else -> Loading()
            }
        }
    }
}

@Composable
private fun AuthorContent(details: AuthorDetails, onBook: (String) -> Unit) {
    // скрытые в настройках типы (статьи, интервью…) убираем сразу, без перезагрузки страницы
    val hidden = AppPrefs.hiddenTypes
    val d = remember(details, hidden) {
        details.copy(sections = details.sections.filter { s -> s.cycleId != null || !ru.moistranicy.app.data.isHiddenSection(s.title) }
            .map { s -> s.copy(books = s.books.filter { !isHiddenType(it.workType) }) }
            .filter { it.books.isNotEmpty() })
    }
    val shelf by ShelfRepository.entries.collectAsState()
    val tracked by CycleRepository.cycles.collectAsState()
    // «не на русском» — по изданиям на FantLab, а не по названию: проверяем книги с нерусским названием
    LaunchedEffect(d.id, d.sections.size) {
        val toCheck = d.sections.flatMap { it.books }
            .filter { it.id.startsWith("fl-") && !it.isCycle && !LangCache.has(it.id) && isForeignTitle(it.title) }
            .distinctBy { it.id }.take(40)
        var failed = 0
        for (part in toCheck.chunked(4)) {
            // сервер не отдаёт сведения об изданиях — остаёмся при проверке по названию
            if (failed >= 4) break
            coroutineScope {
                part.map { b -> async { b to runCatching { FantLab.hasRussianEdition(b.id) } } }.awaitAll()
            }.forEach { (b, res) ->
                if (res.isFailure) { failed++; return@forEach }
                LangCache.put(b.id, res.getOrNull()?.let { !it } ?: isForeignTitle(b.title))
            }
        }
    }
    // все циклы изначально свёрнуты
    val expanded = remember(d.id) { mutableStateMapOf<String, Boolean>() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp)) {
                    AuthorAvatar(d.imageUrl, d.name, 72.dp)
                    // средняя оценка прочитанных и оценённых книг автора
                    val myRating = authorRating(d.id, shelf)
                    if (myRating != null) Box(Modifier.align(Alignment.BottomEnd)) { RatingStar(myRating, size = 30.dp) }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (d.nameOrig.isNotEmpty() && d.nameOrig != d.name) {
                        Text(d.nameOrig, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.years.isNotEmpty()) {
                        Text(d.years, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (d.sections.isEmpty()) {
            item { CenterMessage("Библиография пока недоступна") }
        }

        val cycleSections = d.sections.filter { it.cycleId != null }
        if (cycleSections.isNotEmpty()) {
            item { SectionTitle("Циклы") }
        }
        // дерево циклов: у каждого подцикла родитель — ближайший раздел выше с меньшей глубиной
        val parentOf = IntArray(cycleSections.size) { -1 }
        cycleSections.forEachIndexed { k, sec ->
            parentOf[k] = (k - 1 downTo 0).firstOrNull { cycleSections[it].depth < sec.depth } ?: -1
        }
        val childrenOf = cycleSections.indices.groupBy { parentOf[it] }
        // циклы верхнего уровня — по алфавиту
        val roots = childrenOf[-1].orEmpty().sortedBy { cycleSections[it].title.lowercase().replace('ё', 'е') }

        fun cycleHeader(k: Int, level: Int) {
            val sec = cycleSections[k]
            val cid = sec.cycleId!!
            val open = expanded[cid] == true
            val published = sec.books.filter { it.published && markFor(it, d.id) == null }
            val read = published.count { shelf[it.id]?.status == ReadStatus.READ }
            item(key = "c$cid") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded[cid] = !open }
                        .padding(start = (12 + 18 * level).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // подцикл — ступенькой правее родителя, с уголком, чтобы было видно, куда он входит
                    if (level > 0) {
                        Text("└", style = MaterialTheme.typography.bodyMedium, color = Violet)
                        Spacer(Modifier.width(2.dp))
                    }
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (open) "Свернуть" else "Развернуть",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        sec.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // отслеживаемый или прочитанный целиком цикл — с галочкой и фирменным цветом
                    val done = tracked.containsKey(cid) || (published.isNotEmpty() && read == published.size)
                    Text(
                        (if (done) "✓ " else "") +
                            (if (read > 0) "$read из ${published.size}" else "${published.size} кн."),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // открыть страницу цикла (следить, описание)
                    IconButton(onClick = { onBook(cid) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "О цикле",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        /**
         * Раскрытый цикл: книги по порядку номеров, а подцикл встаёт на место своей первой книги
         * («Мир вечного»: сначала «Охота на охотника» с книгами 1–4, потом «Вечный», «Хоаххин», затем книги 12 и 13).
         */
        fun cycleTree(k: Int, level: Int) {
            cycleHeader(k, level)
            val sec = cycleSections[k]
            val cid = sec.cycleId!!
            if (expanded[cid] != true) return
            val subs = childrenOf[k].orEmpty()
            val subBooks = subs.associateWith { j -> cycleSections[j].books.map { it.id }.toSet() }
            val emitted = HashSet<Int>()
            sec.books.forEachIndexed { i, b ->
                val owner = subs.firstOrNull { j -> b.id in subBooks.getValue(j) }
                if (owner != null) {
                    if (emitted.add(owner)) cycleTree(owner, level + 1)
                    return@forEachIndexed
                }
                // книга, которую показывает подцикл глубже (но не прямой), тоже пропускаем
                if (b.id in sec.nested) return@forEachIndexed
                item(key = "c$cid/${b.id}") {
                    CompactBookLine(
                        number = i + 1,
                        title = b.title,
                        sub = listOfNotNull(b.year, if (!b.published) "ожидается" else null).joinToString(", "),
                        status = shelf[b.id]?.status,
                        published = b.published,
                        indent = 36 + 18 * level,
                        mark = markFor(b, d.id),
                        statusIcon = true,
                        rating = shelf[b.id]?.rating,
                    ) { onBook(b.id) }
                }
            }
            // подциклы без книг в общем списке — в конце
            subs.filter { it !in emitted }.forEach { cycleTree(it, level + 1) }
        }

        roots.forEach { cycleTree(it, 0) }

        d.sections.filter { it.cycleId == null && it.books.isNotEmpty() }.forEachIndexed { si, s ->
            item(key = "s$si") { SectionTitle(s.title) }
            itemsIndexed(s.books, key = { i, _ -> "s$si/$i" }) { _, b ->
                CompactBookLine(
                    number = null,
                    title = b.title,
                    sub = listOfNotNull(b.workType, b.year, if (!b.published) "в планах" else null).joinToString(", "),
                    status = shelf[b.id]?.status,
                    published = b.published,
                    mark = markFor(b, d.id),
                    // как везде: прочитано — звёздочка (с оценкой), иначе глазик
                    statusIcon = !b.isCycle,
                    rating = shelf[b.id]?.rating,
                ) { onBook(b.id) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}
