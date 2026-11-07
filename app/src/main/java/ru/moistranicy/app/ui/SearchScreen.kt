@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import ru.moistranicy.app.data.isHiddenType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.AuthorHit
import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.GoogleBooks
import ru.moistranicy.app.data.Page
import ru.moistranicy.app.data.Typos
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight

/** Постраничная загрузка результатов. */
class PagedState<T>(private val loader: suspend (query: String, page: Int) -> Page<T>) {
    var items by mutableStateOf<List<T>>(emptyList())
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var loadedQuery by mutableStateOf<String?>(null)
    private var page = 1
    private var job: Job? = null

    fun search(scope: CoroutineScope, query: String) {
        job?.cancel()
        job = scope.launch {
            loading = true; error = null; items = emptyList(); hasMore = false; page = 1
            try {
                val p = loader(query, 1)
                items = p.items; hasMore = p.hasMore; loadedQuery = query
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e; loadedQuery = query
            } finally {
                loading = false
            }
        }
    }

    fun more(scope: CoroutineScope) {
        val q = loadedQuery ?: return
        if (loadingMore || !hasMore) return
        scope.launch {
            loadingMore = true
            try {
                val p = loader(q, page + 1)
                page += 1
                items = items + p.items
                hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hasMore = false
            } finally {
                loadingMore = false
            }
        }
    }
}

private enum class SearchMode(val title: String) {
    WORKS("Книги"), AUTHORS("Авторы"), GOOGLE("Google Книги")
}

/** Состояние поиска живёт, пока открыто приложение. */
private object SearchHolder {
    var query by mutableStateOf("")
    var mode by mutableStateOf(SearchMode.WORKS)
    var works = PagedState<Book> { q, p -> FantLab.searchWorks(q, p) }
    var authors = PagedState<AuthorHit> { q, p -> FantLab.searchAuthors(q, p) }
    var google = PagedState<Book> { q, p -> GoogleBooks.search(q, p) }
    /** Меняется при сбросе — поле ввода получает фокус. */
    var resetNonce by mutableIntStateOf(0)
    /** Пользователь набирает текст (показываем подсказки авторов). */
    var typing by mutableStateOf(false)
    /** «Возможно, вы имели в виду …» для запроса didYouMeanFor. */
    var didYouMean by mutableStateOf<String?>(null)
    var didYouMeanFor by mutableStateOf<String?>(null)
}

/** Полный сброс поиска (повторное нажатие на вкладку «Поиск»). */
fun resetSearch() {
    SearchHolder.query = ""
    SearchHolder.mode = SearchMode.WORKS
    SearchHolder.works = PagedState { q, p -> FantLab.searchWorks(q, p) }
    SearchHolder.authors = PagedState { q, p -> FantLab.searchAuthors(q, p) }
    SearchHolder.google = PagedState { q, p -> GoogleBooks.search(q, p) }
    SearchHolder.typing = false
    SearchHolder.didYouMean = null
    SearchHolder.didYouMeanFor = null
    SearchHolder.resetNonce++
}

@Composable
fun SearchScreen(onBook: (String) -> Unit, onAuthor: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val nonce = SearchHolder.resetNonce
    LaunchedEffect(nonce) {
        if (nonce > 0) runCatching { focusRequester.requestFocus() }
    }

    fun run() {
        val q = SearchHolder.query.trim()
        if (q.length < 2) return
        focus.clearFocus()
        SearchHolder.typing = false
        when (SearchHolder.mode) {
            SearchMode.WORKS -> SearchHolder.works.search(scope, q)
            SearchMode.AUTHORS -> SearchHolder.authors.search(scope, q)
            SearchMode.GOOGLE -> SearchHolder.google.search(scope, q)
        }
    }

    Scaffold(contentWindowInsets = NoInsets, topBar = { AppTopBar("Мой поиск") }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = SearchHolder.query,
                onValueChange = { SearchHolder.query = it; SearchHolder.typing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focusRequester),
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
                        onClick = {
                            SearchHolder.mode = m
                            val state = when (m) {
                                SearchMode.WORKS -> SearchHolder.works.loadedQuery
                                SearchMode.AUTHORS -> SearchHolder.authors.loadedQuery
                                SearchMode.GOOGLE -> SearchHolder.google.loadedQuery
                            }
                            if (state != SearchHolder.query.trim()) run()
                        },
                        label = { Text(m.title) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            // подсказки авторов при наборе (в т. ч. с исправлением раскладки и опечаток)
            val q = SearchHolder.query.trim()
            val hints = remember(q) {
                if (q.length < 3) emptyList() else {
                    val layout = Typos.fixLayout(q)
                    val base = Typos.suggestKnown(layout ?: q, 5).map { it.first }
                    (listOfNotNull(layout) + base).distinct().filter { !it.equals(q, true) }.take(5)
                }
            }
            if (SearchHolder.typing && hints.isNotEmpty()) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    hints.forEach { h ->
                        Text(
                            h,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { SearchHolder.query = h; run() }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // «Возможно, вы имели в виду …», если поиск ничего не нашёл
            val current: PagedState<*> = when (SearchHolder.mode) {
                SearchMode.WORKS -> SearchHolder.works
                SearchMode.AUTHORS -> SearchHolder.authors
                SearchMode.GOOGLE -> SearchHolder.google
            }
            val doneEmpty = !current.loading && current.loadedQuery != null && current.items.isEmpty()
            LaunchedEffect(doneEmpty, current.loadedQuery) {
                val lq = current.loadedQuery
                if (doneEmpty && lq != null && SearchHolder.didYouMeanFor != lq) {
                    SearchHolder.didYouMeanFor = lq
                    SearchHolder.didYouMean = null
                    SearchHolder.didYouMean = Typos.didYouMean(lq)
                }
            }
            val dym = SearchHolder.didYouMean
            if (doneEmpty && dym != null && SearchHolder.didYouMeanFor == current.loadedQuery) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Возможно, вы имели в виду: ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        dym,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { SearchHolder.query = dym; run() },
                    )
                }
            }

            if (SearchHolder.query.trim().length >= 2) {
                ExternalSourcesRow(
                    SearchHolder.query,
                    "Искать также:",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }
            when (SearchHolder.mode) {
                SearchMode.WORKS -> BookResults(SearchHolder.works, ::run, onBook)
                SearchMode.GOOGLE -> BookResults(SearchHolder.google, ::run, onBook)
                SearchMode.AUTHORS -> {
                    val st = SearchHolder.authors
                    Results(st, ::run) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(st.items) { h -> AuthorRow(h) { onAuthor(h.id) }; RowDivider() }
                            if (st.hasMore) item { LoadMoreFooter(st.loadingMore) { st.more(scope) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookResults(st: PagedState<Book>, retry: () -> Unit, onBook: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Results(st, retry) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(st.items.filter { !isHiddenType(it.workType) }) { b -> BookRow(b) { onBook(b.id) }; RowDivider() }
            if (st.hasMore) item { LoadMoreFooter(st.loadingMore) { st.more(scope) } }
        }
    }
}

@Composable
private fun <T> Results(st: PagedState<T>, retry: () -> Unit, content: @Composable () -> Unit) {
    val err = st.error
    when {
        st.loading -> Loading()
        err != null -> CenterMessage(errorText(err), "Повторить", retry)
        st.loadedQuery == null -> CenterMessage(
            "Найдите книгу и добавьте её на полку.\n\n«Книги» и «Авторы» — база FantLab, " +
                "в ней есть циклы и рейтинги. Нет нужной книги — попробуйте «Google Книги».",
        )
        st.items.isEmpty() -> CenterMessage("Ничего не найдено")
        else -> content()
    }
}
