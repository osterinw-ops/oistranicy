@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.clickable
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
import ru.moistranicy.app.data.Recommendation
import ru.moistranicy.app.data.Recommender
import ru.moistranicy.app.data.ShelfRepository
import java.util.Locale

private const val PER_PAGE = 5

/** Рекомендации считаются один раз за сессию (или по кнопке «Обновить»). */
private object RecsState {
    var items by mutableStateOf<List<Recommendation>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableIntStateOf(0)
}

@Composable
fun RecommendationsScreen(onBook: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

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
                RecsState.error = e.message ?: "ошибка сети"
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

    // уже добавленное на полку не показываем
    val shelf by ShelfRepository.entries.collectAsState()
    val shelfIds = shelf.keys
    val all = RecsState.items.orEmpty().filter { it.book.id !in shelfIds }
    val pages = (all.size + PER_PAGE - 1) / PER_PAGE
    val page = RecsState.page.coerceIn(0, (pages - 1).coerceAtLeast(0))
    val shown = all.drop(page * PER_PAGE).take(PER_PAGE)

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                "Мои рекомендации",
                actions = {
                    if (RecsState.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                    } else {
                        IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
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
                err != null && all.isEmpty() -> CenterMessage("Не удалось подобрать: $err", "Повторить") { load() }
                all.isEmpty() -> CenterMessage(
                    "Пока нечего посоветовать.\n\nОтметьте несколько книг как «Прочитал» и поставьте оценки — " +
                        "рекомендации подбираются по тому, что вам понравилось.",
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    shown.forEach { r ->
                        RecCard(r) { onBook(r.book.id) }
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
                        OutlinedButton(onClick = { RecsState.page = page + 1 }, enabled = page < pages - 1) { Text("Ещё 5 →") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecCard(r: Recommendation, onClick: () -> Unit) {
    val b = r.book
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        BookCover(b, Modifier.width(72.dp), corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
}
