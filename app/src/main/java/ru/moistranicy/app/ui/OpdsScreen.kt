@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.reader.BookImporter
import ru.moistranicy.app.data.reader.Opds
import ru.moistranicy.app.data.reader.OpdsCatalog
import ru.moistranicy.app.data.reader.OpdsEntry
import ru.moistranicy.app.data.reader.OpdsFeed

/**
 * OPDS-каталоги: список своих каталогов (добавляются через форму) и просмотр каталога —
 * разделы, поиск, книги со скачиванием FB2/EPUB прямо в читалку.
 */
@Composable
fun OpdsScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalogs by remember { mutableStateOf(Opds.catalogs()) }
    var adding by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<OpdsCatalog?>(null) }
    /** Стек открытых лент: «Назад» возвращает к предыдущей. */
    val stack = remember { mutableStateListOf<OpdsFeed>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }

    fun open(url: String, cat: OpdsCatalog, append: Boolean = false) {
        loading = true; error = null
        scope.launch {
            try {
                val f = Opds.load(url, cat)
                if (append && stack.isNotEmpty()) {
                    val top = stack.removeAt(stack.lastIndex)
                    stack.add(top.copy(entries = top.entries + f.entries, next = f.next))
                } else stack.add(f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Каталог не открылся"
            } finally {
                loading = false
            }
        }
    }

    fun back() {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            catalog != null -> { stack.clear(); catalog = null; error = null }
            else -> onBack()
        }
    }
    BackHandler { back() }

    fun download(e: OpdsEntry) {
        val cat = catalog ?: return
        val link = e.downloads.firstOrNull() ?: return
        busy = e.title
        scope.launch {
            try {
                val f = Opds.download(context, link, cat)
                val b = BookImporter.import(context, Uri.fromFile(f))
                f.delete()
                onOpenBook(b.id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = ex.message ?: "Книга не скачалась"
            } finally {
                busy = null
            }
        }
    }

    val feed = stack.lastOrNull()
    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        feed?.title?.ifBlank { null } ?: catalog?.title ?: "OPDS-каталоги",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (catalog == null) {
                // список своих каталогов
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(
                            "Добавьте адрес своего OPDS-каталога — домашней библиотеки, Calibre или магазина. " +
                                "Книги в FB2 и EPUB скачиваются сразу в читалку.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить каталог")
                        }
                    }
                    items(catalogs, key = { it.url }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { catalog = c; stack.clear(); open(c.url, c) }
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title.ifBlank { c.url }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(c.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = {
                                catalogs = catalogs - c; Opds.saveCatalogs(catalogs)
                            }) { Icon(Icons.Filled.Delete, "Удалить каталог", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            } else {
                feed?.searchTemplate?.let { tpl ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            query, { query = it }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Поиск в каталоге") },
                        )
                        IconButton(enabled = query.isNotBlank(), onClick = { open(Opds.searchUrl(tpl, query.trim()), catalog!!) }) {
                            Icon(Icons.Filled.Search, "Найти")
                        }
                    }
                }
                when {
                    feed == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    feed == null -> Column(Modifier.padding(24.dp)) {
                        Text(error ?: "Каталог не открылся", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { open(catalog!!.url, catalog!!) }) { Text("Повторить") }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (feed.entries.isEmpty()) item {
                            Text("Здесь пусто", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(feed.entries) { e -> EntryRow(e, busy == e.title, onNav = { url -> open(url, catalog!!) }, onDownload = { download(e) }) }
                        if (feed.next != null) item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                                else OutlinedButton(onClick = { open(feed.next, catalog!!, append = true) }) { Text("Показать ещё") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddCatalogDialog(
        onDismiss = { adding = false },
        onAdd = { c ->
            catalogs = catalogs.filter { it.url != c.url } + c
            Opds.saveCatalogs(catalogs)
            adding = false
            catalog = c; stack.clear(); open(c.url, c)
        },
    )
    if (error != null && feed != null) AlertDialog(
        onDismissRequest = { error = null },
        title = { Text("Не получилось") },
        text = { Text(error!!) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } },
    )
}

@Composable
private fun EntryRow(e: OpdsEntry, downloading: Boolean, onNav: (String) -> Unit, onDownload: () -> Unit) {
    val nav = e.navigation
    val canRead = e.downloads.isNotEmpty()
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = nav != null || canRead) { if (canRead) onDownload() else nav?.let { onNav(it.href) } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRead) {
            Box(Modifier.size(width = 48.dp, height = 70.dp).clip(RoundedCornerShape(5.dp)).background(Violet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                if (e.cover != null) AsyncImage(e.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(e.title.take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (canRead) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.author.isNotEmpty()) Text(e.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.summary.isNotEmpty()) Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (canRead) Text(
                if (downloading) "Скачиваю…" else "Читать · " + Opds.formatOf(e.downloads.first()),
                style = MaterialTheme.typography.labelMedium, color = Violet,
            )
        }
        if (downloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (OpdsCatalog) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый каталог") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Адрес каталога") }, singleLine = true,
                    placeholder = { Text("https://…/opds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(title, { title = it }, label = { Text("Название (необязательно)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Логин (если нужен)") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                var u = url.trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                onAdd(OpdsCatalog(title.trim().ifEmpty { Uri.parse(u).host ?: u }, u, user.trim(), password))
            }) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
