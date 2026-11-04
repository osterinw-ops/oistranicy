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
            val d = FantLab.author(id)
            AuthorCache.map[id] = d
            details = d
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
private fun AuthorContent(d: AuthorDetails, onBook: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val tracked by CycleRepository.cycles.collectAsState()
    // все циклы изначально свёрнуты
    val expanded = remember(d.id) { mutableStateMapOf<String, Boolean>() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(36.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (d.imageUrl != null) {
                        AsyncImage(
                            model = d.imageUrl,
                            contentDescription = d.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
        cycleSections.forEach { s ->
            val cid = s.cycleId!!
            val open = expanded[cid] == true
            val published = s.books.filter { it.published }
            val read = published.count { shelf[it.id]?.status == ReadStatus.READ }
            item(key = "c$cid") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded[cid] = !open }
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (open) "Свернуть" else "Развернуть",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        (if (tracked.containsKey(cid)) "✓ " else "") +
                            (if (read > 0) "$read из ${published.size}" else "${published.size} кн."),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tracked.containsKey(cid)) MaterialTheme.colorScheme.primary
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
            if (open) {
                itemsIndexed(s.books, key = { _, b -> "c$cid/${b.id}" }) { i, b ->
                    CompactBookLine(
                        number = i + 1,
                        title = b.title,
                        sub = listOfNotNull(b.year, if (!b.published) "ожидается" else null).joinToString(", "),
                        status = shelf[b.id]?.status,
                        published = b.published,
                        indent = 36,
                    ) { onBook(b.id) }
                }
            }
        }

        d.sections.filter { it.cycleId == null && it.books.isNotEmpty() }.forEachIndexed { si, s ->
            item(key = "s$si") { SectionTitle(s.title) }
            itemsIndexed(s.books, key = { i, _ -> "s$si/$i" }) { _, b ->
                CompactBookLine(
                    number = null,
                    title = b.title,
                    sub = listOfNotNull(b.workType, b.year, if (!b.published) "в планах" else null).joinToString(", "),
                    status = shelf[b.id]?.status,
                    published = b.published,
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
