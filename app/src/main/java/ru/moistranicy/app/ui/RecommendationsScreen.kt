@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.RecBlock
import ru.moistranicy.app.R
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import ru.moistranicy.app.data.Recommendation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import ru.moistranicy.app.data.Recommender
import ru.moistranicy.app.data.ShelfRepository
import java.util.Locale

/** Одна длинная страница на 30 книг. */
private const val PER_PAGE = 30

/** Рекомендации считаются один раз за сессию (или по кнопке «Обновить»). */
private object RecsState {
    var items by mutableStateOf<List<Recommendation>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableIntStateOf(0)
    /** Выбранные фильтры: жанр (как в облаке тегов) и период. */
    var genre by mutableStateOf<String?>(null)
    var era by mutableStateOf<String?>(null)
    /** Идёт догрузка жанров и лет. */
    var enriching by mutableStateOf(false)
    /** Книги, для которых уже пытались догрузить данные. */
    val enriched = HashSet<String>()
    /** Жанры, для которых уже подбирали дополнительные книги. */
    val genresLoaded = HashSet<String>()
}

/** Сбросить фильтры (двойное нажатие на вкладку, кнопка «Обновить»). */
fun resetRecsFilters() {
    RecsState.genre = null
    RecsState.era = null
    RecsState.page = 0
}

/** Открыть рекомендации сразу с фильтром по жанру (нажатие на тег). */
fun showRecsForGenre(tag: String) {
    RecsState.genre = tag
    RecsState.era = null
    RecsState.page = 0
}

/** Период книги для фильтра по году. */
private fun eraOf(year: String?): String? {
    val y = year?.toIntOrNull() ?: return null
    return if (y >= 1990) "${y / 10 * 10}-е" else "до 1990"
}

@Composable
fun RecommendationsScreen(onBook: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }

    fun load() {
        if (RecsState.loading) return
        RecsState.loading = true
        RecsState.error = null
        scope.launch {
            try {
                RecsState.items = Recommender.build()
                RecsState.page = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecsState.error = ru.moistranicy.app.data.friendlyError(e)
            } finally {
                RecsState.loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (RecsState.items == null) load() }
    val resetNonce = TabReset.nonces["recs"] ?: 0
    LaunchedEffect(resetNonce) {
        if (resetNonce > 0) RecsState.page = 0
        scroll.scrollTo(0)
    }
    LaunchedEffect(RecsState.page) { scroll.scrollTo(0) }

    // выбран жанр — дополняем список книгами именно этого жанра (один раз за сессию)
    val focusGenre = RecsState.genre
    val recsReady = RecsState.items != null
    LaunchedEffect(focusGenre, recsReady) {
        if (focusGenre == null || !recsReady || !RecsState.genresLoaded.add(focusGenre)) return@LaunchedEffect
        val extra = Recommender.forGenre(focusGenre) { b -> b.genres.any { genreKey(it) == focusGenre } }
        val known = RecsState.items.orEmpty().map { it.book.id }.toSet()
        RecsState.items = RecsState.items.orEmpty() + extra.filter { it.book.id !in known }
    }

    // жанры и годы догружаем отдельно: список показывается сразу, фильтры наполняются
    val itemIds = RecsState.items?.map { it.book.id }.orEmpty()
    LaunchedEffect(itemIds) {
        val need = RecsState.items.orEmpty().map { it.book }
            .filter { (it.genres.isEmpty() || it.year == null) && it.id !in RecsState.enriched }
        if (need.isEmpty()) return@LaunchedEffect
        RecsState.enriching = true
        try {
            Recommender.enrich(need) { loaded ->
                val byId = loaded.associateBy { it.id }
                RecsState.enriched += byId.keys
                RecsState.items = RecsState.items?.map { r ->
                    val fresh = byId[r.book.id] ?: return@map r
                    r.copy(
                        book = r.book.copy(
                            genres = fresh.genres.ifEmpty { r.book.genres },
                            year = r.book.year ?: fresh.year,
                            coverUrl = r.book.coverUrl ?: fresh.coverUrl,
                            annotation = r.book.annotation.ifBlank { fresh.annotation },
                        ),
                    )
                }
            }
        } finally {
            RecsState.enriching = false
        }
    }

    // уже добавленное на полку не показываем
    val shelf by ShelfRepository.entries.collectAsState()
    val shelfIds = shelf.keys
    // скрытые книги и авторы исчезают сразу, без пересчёта
    val blockedBooks by RecBlock.books.collectAsState()
    val blockedAuthors by RecBlock.authors.collectAsState()
    val pool = RecsState.items.orEmpty().filter {
        it.book.id !in shelfIds && it.book.id !in blockedBooks && !RecBlock.isBlocked(it.book) &&
            !ru.moistranicy.app.data.isHiddenType(it.book.workType)
    }
    blockedAuthors.size // подписка на изменения списка авторов
    val genreFilter = RecsState.genre
    val eraFilter = RecsState.era
    val readAuthors = remember(shelf) { Recommender.readAuthorKeys() }
    val all = Recommender.arrange(
        pool.filter { r ->
            (genreFilter == null || r.book.genres.any { genreKey(it) == genreFilter }) &&
                (eraFilter == null || eraOf(r.book.year) == eraFilter)
        },
        readAuthors,
    )
    // варианты фильтров: жанры — от частых к редким, периоды — от новых к старым
    val genreOptions = (pool.flatMap { r -> r.book.genres.map(::genreKey).distinct() }
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key } + listOfNotNull(genreFilter)).distinct()
    val eraOptions = (pool.mapNotNull { eraOf(it.book.year) } + listOfNotNull(eraFilter)).distinct()
        .sortedByDescending { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pages = (all.size + PER_PAGE - 1) / PER_PAGE
    val page = RecsState.page.coerceIn(0, (pages - 1).coerceAtLeast(0))
    val shown = all.drop(page * PER_PAGE).take(PER_PAGE)

    Scaffold(
        contentWindowInsets = NoInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                "Мои рекомендации",
                actions = {
                    if (RecsState.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                    } else {
                        IconButton(onClick = {
                            resetRecsFilters()
                            RecsState.genresLoaded.clear()
                            RecsState.enriched.clear()
                            load()
                        }) { Icon(Icons.Filled.Refresh, "Обновить") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
        if (genreOptions.isNotEmpty() || eraOptions.isNotEmpty()) {
            FilterRow(eraOptions, eraFilter) { RecsState.era = it; RecsState.page = 0 }
            FilterRow(genreOptions, genreFilter) { RecsState.genre = it; RecsState.page = 0 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val err = RecsState.error
            when {
                RecsState.items == null && RecsState.loading -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Подбираю книги по вашей полке…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                err != null && pool.isEmpty() -> CenterMessage("Не удалось подобрать книги.\n$err", "Повторить") { load() }
                all.isEmpty() && (genreFilter != null || eraFilter != null) -> {
                    if (RecsState.enriching) Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Подбираю книги…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else CenterMessage("С такими фильтрами книг нет.", "Сбросить фильтры") {
                        RecsState.genre = null
                        RecsState.era = null
                        RecsState.page = 0
                    }
                }
                all.isEmpty() -> CenterMessage(
                    "Пока нечего посоветовать.\n\nОтметьте несколько книг как «Прочитал» и поставьте оценки — " +
                        "рекомендации подбираются по тому, что вам понравилось.",
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    shown.forEach { r ->
                        RecCard(
                            r,
                            onHideBook = {
                                RecBlock.blockBook(r.book.id)
                                scope.launch {
                                    val res = snackbar.showSnackbar("Книга скрыта навсегда", "Отменить", duration = SnackbarDuration.Short)
                                    if (res == SnackbarResult.ActionPerformed) RecBlock.unblockBook(r.book.id)
                                }
                            },
                            onHideAuthor = {
                                RecBlock.blockAuthors(r.book.authors)
                                scope.launch {
                                    val res = snackbar.showSnackbar(
                                        "${r.book.authorsLine} — скрыт на полгода", "Отменить", duration = SnackbarDuration.Short,
                                    )
                                    if (res == SnackbarResult.ActionPerformed) RecBlock.unblockAuthors(r.book.authors)
                                }
                            },
                        ) { onBook(r.book.id) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { RecsState.page = page - 1 }, enabled = page > 0) { Text("← Назад") }
                        Text(
                            "${page + 1} из $pages",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        OutlinedButton(onClick = { RecsState.page = page + 1 }, enabled = page < pages - 1) { Text("Ещё $PER_PAGE →") }
                    }
                }
            }
        }
        }
    }
}

/** Строка фильтров: повторное нажатие снимает выбор. */
@Composable
private fun FilterRow(options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    if (options.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(if (o == selected) null else o) },
                label = { Text(o, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun RecCard(
    r: Recommendation,
    onHideBook: () -> Unit,
    onHideAuthor: () -> Unit,
    onClick: () -> Unit,
) {
    val b = r.book
    Box(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        BookCover(b, Modifier.width(72.dp), corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 64.dp),
            )
            if (b.authors.isNotEmpty()) {
                Text(b.authorsLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            val meta = listOfNotNull(
                b.workType,
                b.year,
                b.communityRating?.let { "★ " + String.format(Locale("ru"), "%.1f", it) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (b.annotation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    b.annotation,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
    // «не рекомендовать» — иконки в правом верхнем углу карточки
    Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
        IconButton(onClick = onHideBook, modifier = Modifier.size(34.dp)) {
            Icon(
                painterResource(R.drawable.ic_thumb_down),
                contentDescription = "Не рекомендовать книгу",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (b.authors.isNotEmpty()) {
            IconButton(onClick = onHideAuthor, modifier = Modifier.size(34.dp)) {
                Icon(
                    painterResource(R.drawable.ic_person_off),
                    contentDescription = "Не рекомендовать автора",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    }
}
