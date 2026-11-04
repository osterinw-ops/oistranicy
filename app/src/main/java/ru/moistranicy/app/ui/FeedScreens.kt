@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.OpdsClient
import ru.moistranicy.app.data.OpdsEntry

/** Состояние постраничной загрузки OPDS-ленты. */
class FeedState {
    var entries by mutableStateOf<List<OpdsEntry>>(emptyList())
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var loaded by mutableStateOf(false)
    var title by mutableStateOf("")
    private var job: Job? = null

    fun load(scope: CoroutineScope, path: String) {
        job?.cancel()
        job = scope.launch {
            loading = true; error = null; entries = emptyList(); next = null
            try {
                val feed = OpdsClient.fetch(path)
                entries = feed.entries; next = feed.next; title = feed.title
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e; loaded = true
            } finally {
                loading = false
            }
        }
    }

    fun loadMore(scope: CoroutineScope) {
        val n = next ?: return
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            try {
                val feed = OpdsClient.fetch(n)
                entries = entries + feed.entries
                next = feed.next
            } catch (e: Exception) {
                next = null
            } finally {
                loadingMore = false
            }
        }
    }
}

/** Открытые разделы каталога держим в памяти, чтобы «Назад» не перезагружал список. */
object FeedCache {
    private val map = HashMap<String, FeedState>()
    fun get(href: String): FeedState = map.getOrPut(href) { FeedState() }
}

private enum class SearchMode(val title: String) { BOOKS("Книги"), AUTHORS("Авторы") }

/** Поиск живёт, пока открыто приложение, чтобы не терять результаты при переходах. */
private object SearchHolder {
    var query by mutableStateOf("")
    var mode by mutableStateOf(SearchMode.BOOKS)
    val feed = FeedState()
}

@Composable
fun SearchScreen(onBook: (String) -> Unit, onFeed: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val feed = SearchHolder.feed

    fun run() {
        val q = SearchHolder.query.trim()
        if (q.length < 2) return
        focus.clearFocus()
        val path = if (SearchHolder.mode == SearchMode.BOOKS) OpdsClient.searchBooksPath(q)
        else OpdsClient.searchAuthorsPath(q)
        feed.load(scope, path)
    }

    Scaffold(contentWindowInsets = NoInsets, topBar = { TopAppBar(title = { Text("Поиск") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = SearchHolder.query,
                onValueChange = { SearchHolder.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Название книги или автор") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (SearchHolder.query.isNotEmpty()) {
                        IconButton(onClick = { SearchHolder.query = "" }) { Icon(Icons.Filled.Clear, "Очистить") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SearchMode.entries.forEach { m ->
                    FilterChip(
                        selected = SearchHolder.mode == m,
                        onClick = { SearchHolder.mode = m; run() },
                        label = { Text(m.title) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            when {
                feed.loading -> Loading()
                feed.error != null -> CenterMessage(errorText(feed.error!!), "Повторить") { run() }
                !feed.loaded -> CenterMessage("Найдите книгу и добавьте её на полку.\nПоиск по каталогу Флибусты.")
                feed.entries.isEmpty() -> CenterMessage("Ничего не найдено")
                else -> EntryList(
                    entries = feed.entries,
                    hasMore = feed.next != null,
                    loadingMore = feed.loadingMore,
                    onLoadMore = { feed.loadMore(scope) },
                    onBook = onBook,
                    onFeed = onFeed,
                )
            }
        }
    }
}

/** Универсальный экран раздела каталога: цикл, автор, подборки автора. */
@Composable
fun FeedScreen(
    title: String,
    href: String,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    onFeed: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val feed = remember(href) { FeedCache.get(href) }
    LaunchedEffect(href) { if (!feed.loaded && !feed.loading) feed.load(scope, href) }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(title.ifBlank { feed.title }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            when {
                feed.loading || !feed.loaded -> Loading()
                feed.error != null -> CenterMessage(errorText(feed.error!!), "Повторить") { feed.load(scope, href) }
                feed.entries.isEmpty() -> CenterMessage("Здесь пусто")
                else -> EntryList(
                    entries = feed.entries,
                    hasMore = feed.next != null,
                    loadingMore = feed.loadingMore,
                    onLoadMore = { feed.loadMore(scope) },
                    onBook = onBook,
                    onFeed = onFeed,
                )
            }
        }
    }
}
