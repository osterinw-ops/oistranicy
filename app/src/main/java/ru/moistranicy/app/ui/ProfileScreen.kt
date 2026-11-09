@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.FONT_SCALES
import ru.moistranicy.app.data.OPTIONAL_TYPES
import ru.moistranicy.app.data.ThemeMode
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import java.util.Calendar
import java.util.Locale
import ru.moistranicy.app.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable

@Composable
fun ProfileScreen(onBook: (String) -> Unit, onUser: (String) -> Unit = {}, onPeople: () -> Unit = {}) {
    val shelf by ShelfRepository.entries.collectAsState()
    val all = shelf.values
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val readThisYear = all.count { e ->
        e.status == ReadStatus.READ && e.finishedAt?.let { yearOf(it) == year } == true
    }
    val rated = all.mapNotNull { it.rating }
    val avg = if (rated.isEmpty()) null else rated.average()
    val topAuthors = all.filter { it.status == ReadStatus.READ }
        .flatMap { it.book.authors.map { a -> a.name } }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(5)

    Scaffold(contentWindowInsets = NoInsets, topBar = { AppTopBar("Мой профиль") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountCard()
            SocialCard(onUser = onUser, onPeople = onPeople)

            // Прочитано по месяцам — последние 12 месяцев, текущий справа
            val readEntries = all.filter { it.status == ReadStatus.READ }
            MonthlyStats(readEntries.mapNotNull { it.finishedAt }, undated = readEntries.count { it.finishedAt == null })

            // Счётчики по статусам
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadStatus.entries.chunked(2).forEach { pair ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { s -> StatTile(s.title, all.count { it.status == s }.toString()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { StatTile("Всего на полке", all.size.toString()) }
                Column(Modifier.weight(1f)) {
                    StatTile("Средняя оценка", avg?.let { String.format(Locale("ru"), "%.1f", it) } ?: "—")
                }
            }

            if (topAuthors.isNotEmpty()) {
                Card {
                    Text("Любимые авторы", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    topAuthors.forEach { (name, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(name, Modifier.weight(1f))
                            Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            SettingsCard()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard() {
    Card {
        Text("Настройки", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Размер шрифта", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            FONT_SCALES.forEachIndexed { i, (label, scale) ->
                val selected = AppPrefs.fontScale == scale
                Text(
                    "А",
                    fontSize = (16 + i * 5).sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { AppPrefs.saveFontScale(scale) }
                        .semantics { contentDescription = "Шрифт: $label" }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Тема", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach { m ->
                val selected = AppPrefs.theme == m
                // выбранная тема — чёрный кружок, остальные — тёмно-серые
                val tint = if (selected) Color.White else Color.White.copy(alpha = 0.85f)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.Black else Color(0xFF5E5A6E))
                        .clickable { AppPrefs.saveTheme(m) }
                        .semantics { contentDescription = "Тема: " + m.title },
                    contentAlignment = Alignment.Center,
                ) {
                    when (m) {
                        ThemeMode.SYSTEM -> Text("А", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint)
                        ThemeMode.DARK -> Icon(painterResource(R.drawable.ic_moon), null, tint = tint, modifier = Modifier.size(22.dp))
                        ThemeMode.LIGHT -> Icon(painterResource(R.drawable.ic_sun), null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Показывать в приложении", style = MaterialTheme.typography.titleSmall)
        // флажки в два столбика
        val half = (OPTIONAL_TYPES.size + 1) / 2
        Row(Modifier.fillMaxWidth()) {
            listOf(OPTIONAL_TYPES.take(half), OPTIONAL_TYPES.drop(half)).forEach { col ->
                Column(Modifier.weight(1f)) {
                    col.forEach { (type, label) ->
                        val shown = type !in AppPrefs.hiddenTypes
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { AppPrefs.setTypeHidden(type, shown) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = shown,
                                onCheckedChange = { AppPrefs.setTypeHidden(type, shown) },
                                modifier = Modifier.size(36.dp),
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Text(
            "Антологии и сборники не показываются: в них те же книги, что и в библиографии.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Данные о книгах: FantLab (fantlab.ru) и Google Книги. Полка хранится только на этом телефоне.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun yearOf(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)


private val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** Столбики «прочитано за месяц» за последние 12 месяцев; текущий месяц — справа и выделен. */
@Composable
private fun MonthlyStats(finished: List<Long>, undated: Int = 0) {
    val now = Calendar.getInstance()
    val curKey = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
    val counts = IntArray(12)
    val cal = Calendar.getInstance()
    for (t in finished) {
        cal.timeInMillis = t
        val k = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
        val idx = 11 - (curKey - k)
        if (idx in 0..11) counts[idx]++
    }
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прочитано по месяцам", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "за год: ${counts.sum()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (undated > 0) {
            // книги, отмеченные прочитанными без даты (например, «предыдущие книги цикла»), в месяцы не попадают
            Text(
                "Ещё ${undated} без даты прочтения — в столбики не входят",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        // высота не фиксирована: подписи месяцев всегда видны, даже с крупным шрифтом
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 0 until 12) {
                val monthIndex = ((curKey - (11 - i)) % 12 + 12) % 12
                val current = i == 11
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (counts[i] > 0) {
                        Text(
                            counts[i].toString(),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            color = if (current) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((86f * counts[i] / max).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                when {
                                    current -> Coral
                                    counts[i] == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> Violet
                                },
                            ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        MONTHS[monthIndex],
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = if (current) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
