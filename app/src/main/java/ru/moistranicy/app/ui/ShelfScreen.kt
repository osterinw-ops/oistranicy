@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfEntry
import ru.moistranicy.app.data.ShelfRepository

@Composable
fun ShelfScreen(onBook: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val statuses = ReadStatus.entries
    val current = statuses[tab]
    val shown = shelf.values.filter { it.status == current }.sortedByDescending { it.updatedAt }

    Scaffold(contentWindowInsets = NoInsets, topBar = { TopAppBar(title = { Text("Моя полка") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                statuses.forEachIndexed { i, s ->
                    val count = shelf.values.count { it.status == s }
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text("${s.title} $count") })
                }
            }
            if (shown.isEmpty()) {
                CenterMessage(
                    when (current) {
                        ReadStatus.READING -> "Сейчас ничего не читаете.\nНайдите книгу во вкладке «Поиск»."
                        ReadStatus.WANT -> "Список «Хочу прочитать» пуст."
                        ReadStatus.READ -> "Здесь появятся прочитанные книги."
                        ReadStatus.DROPPED -> "Брошенных книг нет. Так держать!"
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(shown, key = { it.book.id }) { e -> ShelfTile(e) { onBook(e.book.id) } }
                }
            }
        }
    }
}

@Composable
private fun ShelfTile(e: ShelfEntry, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            BookCover(e.book, Modifier.fillMaxWidth())
            e.rating?.let {
                Text(
                    "★ $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Coral)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(e.book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            e.book.authorsLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
