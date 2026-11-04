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
import androidx.compose.foundation.lazy.items
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
            item {
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(s.books) { b ->
                BookRow(b) { onBook(b.id) }
                RowDivider()
            }
        }
    }
}
