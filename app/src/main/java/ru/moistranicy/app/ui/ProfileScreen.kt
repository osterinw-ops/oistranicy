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
import ru.moistranicy.app.data.Importer
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
fun ProfileScreen(
    onBook: (String) -> Unit,
    onUser: (String) -> Unit = {},
    onPeople: () -> Unit = {},
    onShelf: (ReadStatus) -> Unit = {},
    onAuthor: (String) -> Unit = {},
) {
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
            // плашки статусов ведут в соответствующий раздел полки
            val tiles = ru.moistranicy.app.data.VISIBLE_STATUSES.map { s ->
                Triple<String, String, (() -> Unit)?>(s.title, all.count { it.status == s }.toString()) { onShelf(s) }
            } + listOf(
                Triple<String, String, (() -> Unit)?>("Всего на полке", all.size.toString(), null),
                Triple<String, String, (() -> Unit)?>("Средняя оценка", avg?.let { String.format(Locale("ru"), "%.1f", it) } ?: "—", null),
            )
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (label, value, click) ->
                        Column(Modifier.weight(1f).then(if (click != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable { click() } else Modifier)) {
                            StatTile(label, value)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // любимые жанры — одной строкой через «|», сколько поместится
            val genres = favoriteGenres(all.filter { it.status == ReadStatus.READ })
            if (genres.isNotEmpty()) {
                Card {
                    Text("Любимые жанры", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    GenresLine(genres)
                }
            }

            // облако любимых авторов (бывшая вкладка «Авторы»)
            Text("Любимые авторы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            AuthorsCloudCard(onAuthor = onAuthor, modifier = Modifier.fillMaxWidth().height(380.dp))

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
                // как размер шрифта: выбранный значок чёрный, остальные тёмно-серые, без подложки
                val tint = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { AppPrefs.saveTheme(m) }
                        .semantics { contentDescription = "Тема: " + m.title },
                    contentAlignment = Alignment.Center,
                ) {
                    when (m) {
                        ThemeMode.SYSTEM -> Text("А", fontSize = 24.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = tint)
                        ThemeMode.DARK -> Icon(painterResource(R.drawable.ic_moon), null, tint = tint, modifier = Modifier.size(26.dp))
                        ThemeMode.LIGHT -> Icon(painterResource(R.drawable.ic_sun), null, tint = tint, modifier = Modifier.size(26.dp))
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
        Spacer(Modifier.height(10.dp))
        ImportButton()
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


/** «Импорт прочитанных книг»: инструкция, выбор файла, ход и итог импорта. */
@Composable
private fun ImportButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) Importer.start(context, uri) }
    Text("Импорт", style = MaterialTheme.typography.titleSmall)
    androidx.compose.material3.OutlinedButton(onClick = { Importer.reset(); open = true }) {
        Text("Импорт прочитанных книг из файла")
    }
    if (!open) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Импорт прочитанных книг") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val msg = Importer.message
                when {
                    Importer.running -> {
                        Text("Ищу книги на FantLab: ${Importer.done} из ${Importer.total}…")
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (Importer.total == 0) 0f else Importer.done.toFloat() / Importer.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Отмечено: ${Importer.added}. Можно закрыть окно — импорт продолжится.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    msg != null -> {
                        Text(msg)
                        val miss = Importer.notFound
                        if (miss.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Не нашлись:", style = MaterialTheme.typography.titleSmall)
                            miss.take(30).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            if (miss.size > 30) Text("…и ещё ${miss.size - 30}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        Text("Подойдёт текстовый файл (.txt, .csv) или таблица Excel (.xlsx).")
                        Spacer(Modifier.height(8.dp))
                        Text("Текстовый файл — одна книга в строке:", fontWeight = FontWeight.SemiBold)
                        Text("Сергей Лукьяненко Ночной дозор", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("Таблица — два столбца: автор и название:", fontWeight = FontWeight.SemiBold)
                        Text("Сергей Лукьяненко | Ночной дозор", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "В .txt и .csv столбцы можно разделять табуляцией или точкой с запятой. " +
                                "Книги ищутся на FantLab и отмечаются прочитанными без даты и оценки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!Importer.running) {
                androidx.compose.material3.TextButton(onClick = {
                    picker.launch(
                        arrayOf(
                            "text/*",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                        ),
                    )
                }) { Text(if (Importer.message == null) "Загрузить файл" else "Загрузить ещё файл") }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Закрыть") }
        },
    )
}


/** Жанры через « | » — ровно столько, сколько помещается в одну строку. */
@Composable
private fun GenresLine(genres: List<String>) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = Coral)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val line = remember(genres, maxPx, style) {
            var acc = ""
            for (g in genres) {
                val next = if (acc.isEmpty()) g else "$acc | $g"
                if (measurer.measure(next, style).size.width > maxPx) break
                acc = next
            }
            acc.ifEmpty { genres.first() }
        }
        Text(line, style = style, maxLines = 1)
    }
}
