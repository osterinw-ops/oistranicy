@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import kotlin.math.sqrt

private data class CloudAuthor(val id: String?, val name: String, val count: Int)

// Цвета логотипа: от светло-лавандового (мало книг) до глубокого фиолетового (много)
private val CloudLight = Color(0xFFB9B0FF)
private val CloudMid = Violet          // #7B6CF6
private val CloudDark = Color(0xFF2A1F7A)
private val CloudBg = Color(0xFFF5F3FF)

/**
 * Облако авторов прочитанных книг: размер и насыщенность имени
 * растут с числом прочитанных книг автора.
 */
@Composable
fun AuthorsCloudScreen(onAuthor: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val read = shelf.values.filter { it.status == ReadStatus.READ }

    // группируем по имени; id берём первый известный FantLab-id
    val authors = read.flatMap { e -> e.book.authors.map { it to e.book.id } }
        .groupBy { it.first.name.trim() }
        .filterKeys { it.isNotEmpty() }
        .map { (name, list) ->
            CloudAuthor(
                id = list.firstNotNullOfOrNull { it.first.id?.takeIf { id -> id.startsWith("fl-") } },
                name = name,
                count = list.map { it.second }.distinct().size,
            )
        }

    Scaffold(contentWindowInsets = NoInsets, topBar = { TopAppBar(title = { Text("Авторы") }) }) { pad ->
        if (authors.isEmpty()) {
            Box(Modifier.padding(pad)) {
                CenterMessage("Здесь появятся авторы прочитанных книг.\nОтметьте книгу как «Прочитал».")
            }
            return@Scaffold
        }
        val min = authors.minOf { it.count }
        val max = authors.maxOf { it.count }

        // крупные имена чередуем с мелкими, пары раскладываем от центра к краям —
        // получается облако, а не столбик
        val sorted = authors.sortedWith(compareByDescending<CloudAuthor> { it.count }.thenBy { it.name })
        val zigzag = ArrayList<CloudAuthor>()
        var lo = 0
        var hi = sorted.size - 1
        var takeBig = true
        while (lo <= hi) {
            zigzag += if (takeBig) sorted[lo++] else sorted[hi--]
            takeBig = !takeBig
        }
        val cloud = ArrayDeque<CloudAuthor>()
        zigzag.forEachIndexed { k, a -> if ((k / 2) % 2 == 0) cloud.addLast(a) else cloud.addFirst(a) }

        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Прочитано книг: ${read.size} · авторов: ${authors.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CloudBg)
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                cloud.forEach { a ->
                    val t = if (max == min) 1f else sqrt((a.count - min).toFloat() / (max - min))
                    val color = if (t < 0.5f) lerp(CloudLight, CloudMid, t * 2) else lerp(CloudMid, CloudDark, (t - 0.5f) * 2)
                    val id = a.id
                    Text(
                        a.name,
                        fontSize = (13 + 19 * t).sp,
                        lineHeight = (17 + 21 * t).sp,
                        fontWeight = if (t > 0.66f) FontWeight.Bold else if (t > 0.33f) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = id != null) { id?.let(onAuthor) }
                            .padding(horizontal = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Чем крупнее и темнее имя — тем больше книг автора вы прочитали. Нажмите на имя, чтобы открыть автора.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
