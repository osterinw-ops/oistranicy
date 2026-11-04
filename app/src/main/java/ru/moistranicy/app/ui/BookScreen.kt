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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.moistranicy.app.data.BookCache
import ru.moistranicy.app.data.OpdsClient
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository

@Composable
fun BookScreen(id: String, onBack: () -> Unit, onFeed: (String, String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val entry = shelf[id]
    val book = BookCache.get(id) ?: entry?.book

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
            Box(Modifier.padding(pad)) { CenterMessage("Книга не найдена") }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row {
                BookCover(book, Modifier.width(120.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    book.authors.forEach { a ->
                        Text(
                            a.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (a.id != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clickable(enabled = a.id != null) {
                                    a.id?.let { onFeed(a.name, OpdsClient.authorPath(it)) }
                                }
                                .padding(vertical = 2.dp),
                        )
                    }
                    book.year?.let {
                        Text(
                            "$it г.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val seriesName = book.seriesName
            if (seriesName != null) {
                Spacer(Modifier.height(14.dp))
                val seriesId = book.seriesId
                Text(
                    "Цикл: «$seriesName»" + (book.seriesNumber?.let { ", книга $it" } ?: "") +
                        (if (seriesId != null) "  →" else ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = seriesId != null) {
                        seriesId?.let { onFeed(seriesName, OpdsClient.seriesPath(it)) }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("Статус", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadStatus.entries.forEach { s ->
                    FilterChip(
                        selected = entry?.status == s,
                        onClick = { ShelfRepository.setStatus(book, s) },
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
                    (1..10).forEach { n ->
                        val selected = entry.rating != null && n <= entry.rating
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (selected) Coral else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    ShelfRepository.setRating(id, if (entry.rating == n) null else n)
                                },
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
            Spacer(Modifier.height(24.dp))
        }
    }
}
