package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.AuthorHit
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository

/** Обложка с запасным вариантом: градиент и первая буква названия. */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(
        modifier
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Violet, VioletDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title.take(1).uppercase(),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        if (book.coverUrl != null) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun StatusBadge(status: ReadStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        ReadStatus.READING -> MaterialTheme.colorScheme.primary
        ReadStatus.WANT -> Color(0xFF4FB3E8)
        ReadStatus.READ -> Color(0xFF4CC38A)
        ReadStatus.DROPPED -> Color(0xFF9A93B5)
    }
    Text(
        status.title,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun BookRow(book: Book, onClick: () -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val entry = shelf[book.id]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.width(56.dp), corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(book.workType, book.year).joinToString(", ")
            if (meta.isNotEmpty() || book.communityRating != null) {
                Text(
                    meta + (book.communityRating?.let { (if (meta.isEmpty()) "" else " · ") + "★ " + "%.1f".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authorsLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val series = book.seriesName
            if (series != null) {
                Text(
                    series + (book.seriesNumber?.let { " · книга $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(entry.status)
                    entry.rating?.let {
                        Text(
                            "  ★ $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = Coral,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
fun AuthorRow(hit: AuthorHit, onClick: () -> Unit) = NavRow(hit.name, hit.subtitle, onClick)

/** Подвал списка с кнопкой «Загрузить ещё». */
@Composable
fun LoadMoreFooter(loading: Boolean, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator()
        else OutlinedButton(onClick = onLoadMore) { Text("Загрузить ещё") }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

@Composable
fun CenterMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

fun errorText(e: Throwable): String =
    "Не удалось загрузить данные.\n${e.message ?: e.javaClass.simpleName}\n\nПроверьте подключение к интернету."
