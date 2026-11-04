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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(42.dp))
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
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (d.nameOrig.isNotEmpty() && d.nameOrig != d.name) {
                        Text(d.nameOrig, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.years.isNotEmpty()) {
                        Text(d.years, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (d.sections.isEmpty()) {
            item { CenterMessage("Библиография пока недоступна") }
        }
        d.sections.forEach { s ->
            val cid = s.cycleId
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (cid != null) Modifier.clickable { onBook(cid) } else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (cid != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (cid != null && tracked.containsKey(cid)) {
                        Text("✓ слежу", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (cid != null) {
                itemsIndexed(s.books) { i, b ->
                    NumberedBookRow(i + 1, b, shelf[b.id]?.status) { onBook(b.id) }
                }
            } else {
                items(s.books) { b ->
                    BookRow(b) { onBook(b.id) }
                    RowDivider()
                }
            }
        }
    }
}

/** Строка «1. Название (год)» — как в библиографии на сайтах. */
@Composable
private fun NumberedBookRow(n: Int, b: Book, status: ReadStatus?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$n.", modifier = Modifier.width(30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            buildString {
                append(b.title)
                b.year?.let { append(" ($it)") }
                if (!b.published) append(" — ожидается")
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                status == ReadStatus.READ -> MaterialTheme.colorScheme.onSurfaceVariant
                !b.published -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            },
        )
        if (status != null) {
            Spacer(Modifier.width(8.dp))
            StatusBadge(status)
        }
    }
}
