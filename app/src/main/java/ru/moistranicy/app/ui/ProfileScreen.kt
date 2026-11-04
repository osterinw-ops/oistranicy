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
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.FONT_SCALES
import ru.moistranicy.app.data.ThemeMode
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository
import java.util.Calendar
import java.util.Locale

@Composable
fun ProfileScreen(onBook: (String) -> Unit) {
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

            // Цель на год
            Card {
                Text("Книжный вызов $year", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "$readThisYear из ${AppPrefs.yearGoal}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (readThisYear.toFloat() / AppPrefs.yearGoal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
            }

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
    var goal by remember { mutableStateOf(AppPrefs.yearGoal.toString()) }
    Card {
        Text("Настройки", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it.filter(Char::isDigit).take(4) },
            label = { Text("Цель: книг за год") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            goal.toIntOrNull()?.let { AppPrefs.saveYearGoal(it) }
            goal = AppPrefs.yearGoal.toString()
        }) { Text("Сохранить") }

        Spacer(Modifier.height(4.dp))
        Text("Размер шрифта", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FONT_SCALES.forEach { (label, scale) ->
                FilterChip(
                    selected = AppPrefs.fontScale == scale,
                    onClick = { AppPrefs.saveFontScale(scale) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Тема", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = AppPrefs.theme == m,
                    onClick = { AppPrefs.saveTheme(m) },
                    label = { Text(m.title) },
                )
            }
        }
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
