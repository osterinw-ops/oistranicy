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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.moistranicy.app.data.AuthorStats
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfEntry
import ru.moistranicy.app.data.ShelfRepository
import kotlin.math.sqrt

private data class CloudWord(val key: String, val label: String, val count: Int, val shade: Float?, val id: String?)

// Цвета логотипа: авторы — фиолетовая гамма, теги — коралловая (закладка)
private val VioletLight = Color(0xFFB9B0FF)
private val VioletDark = Color(0xFF2A1F7A)
private val CoralLight = Color(0xFFFFC2B2)
private val CoralDark = Color(0xFFB8401F)
private val CloudBg = Color(0xFFF5F3FF)
private val TagBg = Color(0xFFFFF4F0)

/** Жанры прочитанных книг, у которых их ещё нет, догружаем с FantLab (по одной за раз). */
private val genreTried = HashSet<String>()

private suspend fun enrichGenres(read: List<ShelfEntry>) {
    for (e in read) {
        val b = e.book
        if (b.genres.isNotEmpty() || !b.id.startsWith("fl-")) continue
        if (!genreTried.add(b.id)) continue
        val d = runCatching { FantLab.work(b.id) }.getOrNull() ?: continue
        if (d.book.genres.isNotEmpty()) ShelfRepository.refreshBook(e.book.copy(genres = d.book.genres))
        delay(150)
    }
}

/** Раскладка облака: крупные слова чередуются с мелкими, пары — от центра к краям. */
private fun <T> cloudOrder(sortedDesc: List<T>): List<T> {
    val zigzag = ArrayList<T>()
    var lo = 0
    var hi = sortedDesc.size - 1
    var big = true
    while (lo <= hi) {
        zigzag += if (big) sortedDesc[lo++] else sortedDesc[hi--]
        big = !big
    }
    val out = ArrayDeque<T>()
    zigzag.forEachIndexed { k, a -> if ((k / 2) % 2 == 0) out.addLast(a) else out.addFirst(a) }
    return out.toList()
}

@Composable
fun AuthorsCloudScreen(onAuthor: (String) -> Unit, onBook: (String) -> Unit) {
    val shelf by ShelfRepository.entries.collectAsState()
    val totals by AuthorStats.totals.collectAsState()
    val read = shelf.values.filter { it.status == ReadStatus.READ }
    var tagSheet by remember { mutableStateOf<String?>(null) }

    // ---- авторы: размер — сколько прочитал я, цвет — сколько написал автор ----
    val authors = read.flatMap { e -> e.book.authors.map { it to e.book.id } }
        .groupBy { it.first.name.trim() }
        .filterKeys { it.isNotEmpty() }
        .map { (name, list) ->
            val id = list.firstNotNullOfOrNull { it.first.id?.takeIf { x -> x.startsWith("fl-") } }
            Triple(name, id, list.map { it.second }.distinct().size)
        }
    val known = authors.mapNotNull { (_, id, _) -> id?.let { totals[it] } }
    val tMin = known.minOrNull() ?: 0
    val tMax = known.maxOrNull() ?: 0
    val authorWords = authors.map { (name, id, count) ->
        val total = id?.let { totals[it] }
        val shade = total?.let { if (tMax == tMin) 0.6f else sqrt((it - tMin).toFloat() / (tMax - tMin)) }
        CloudWord(name, name, count, shade, id)
    }

    // ---- теги: жанры прочитанных книг ----
    val tagWords = read.flatMap { e -> e.book.genres.map { it.trim().lowercase() to e.book.id } }
        .filter { it.first.isNotEmpty() }
        .groupBy { it.first }
        .map { (tag, list) -> CloudWord(tag, tag, list.map { it.second }.distinct().size, null, null) }

    LaunchedEffect(read.size) {
        AuthorStats.ensure(authors.mapNotNull { it.second })
        enrichGenres(read)
    }

    val resetNonce = TabReset.nonces["authors"] ?: 0
    val authorsScroll = rememberScrollState()
    val tagsScroll = rememberScrollState()
    LaunchedEffect(resetNonce) {
        authorsScroll.scrollTo(0)
        tagsScroll.scrollTo(0)
    }

    Scaffold(contentWindowInsets = NoInsets, topBar = { AppTopBar("Мои авторы") }) { pad ->
        if (authors.isEmpty()) {
            Box(Modifier.padding(pad)) {
                CenterMessage("Здесь появятся авторы и жанры прочитанных книг.\nОтметьте книгу как «Прочитал».")
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "Прочитано книг: ${read.size} · авторов: ${authors.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            // 2/3 — облако авторов
            Cloud(
                words = authorWords,
                bg = CloudBg,
                light = VioletLight,
                mid = Violet,
                dark = VioletDark,
                minSp = 13f,
                maxSp = 32f,
                scroll = authorsScroll,
                modifier = Modifier.weight(2f),
            ) { w -> w.id?.let(onAuthor) }
            Spacer(Modifier.height(8.dp))
            // 1/3 — облако тегов
            if (tagWords.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TagBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Жанры загружаются…",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralDark.copy(alpha = 0.6f),
                    )
                }
            } else {
                Cloud(
                    words = tagWords,
                    bg = TagBg,
                    light = CoralLight,
                    mid = Coral,
                    dark = CoralDark,
                    minSp = 12f,
                    maxSp = 26f,
                    scroll = tagsScroll,
                    modifier = Modifier.weight(1f),
                ) { w -> tagSheet = w.key }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val tag = tagSheet
    if (tag != null) {
        val books = read.filter { e -> e.book.genres.any { it.trim().lowercase() == tag } }
            .sortedByDescending { it.finishedAt ?: 0 }
        ModalBottomSheet(onDismissRequest = { tagSheet = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    tag.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "Прочитано книг: ${books.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    books.forEach { e ->
                        CompactBookLine(null, e.book.title, e.book.authorsLine) {
                            tagSheet = null
                            onBook(e.book.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Облако слов. Размер — по count, цвет — по shade (если задан) или по count.
 */
@Composable
private fun Cloud(
    words: List<CloudWord>,
    bg: Color,
    light: Color,
    mid: Color,
    dark: Color,
    minSp: Float,
    maxSp: Float,
    scroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    onClick: (CloudWord) -> Unit,
) {
    val cMin = words.minOfOrNull { it.count } ?: 0
    val cMax = words.maxOfOrNull { it.count } ?: 0
    val ordered = cloudOrder(words.sortedWith(compareByDescending<CloudWord> { it.count }.thenBy { it.label }))
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                ordered.forEach { w ->
                    val size = if (cMax == cMin) 0.6f else sqrt((w.count - cMin).toFloat() / (cMax - cMin))
                    val shade = w.shade ?: size
                    val color = if (shade < 0.5f) lerp(light, mid, shade * 2) else lerp(mid, dark, (shade - 0.5f) * 2)
                    Text(
                        w.label,
                        fontSize = (minSp + (maxSp - minSp) * size).sp,
                        lineHeight = (minSp + 4 + (maxSp - minSp) * size * 1.1f).sp,
                        fontWeight = if (size > 0.66f) FontWeight.Bold else if (size > 0.33f) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onClick(w) }
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
