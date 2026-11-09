package ru.moistranicy.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Строка файла импорта: либо два столбца (автор, название), либо «Автор Название» одной строкой. */
data class ImportLine(val author: String?, val title: String?, val raw: String)

/**
 * Импорт прочитанных книг из текстового файла (.txt, .csv) или таблицы Excel (.xlsx).
 * Каждая строка ищется на FantLab, найденные книги отмечаются прочитанными (без даты и оценки).
 */
object Importer {
    var running by mutableStateOf(false)
        private set
    var total by mutableIntStateOf(0)
        private set
    var done by mutableIntStateOf(0)
        private set
    var added by mutableIntStateOf(0)
        private set
    var already by mutableIntStateOf(0)
        private set
    var notFound by mutableStateOf<List<String>>(emptyList())
        private set
    /** Итог или ошибка чтения файла; null — импорт ещё не запускали. */
    var message by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun reset() {
        if (running) return
        message = null
        notFound = emptyList()
        total = 0; done = 0; added = 0; already = 0
    }

    fun start(context: Context, uri: Uri) {
        if (running) return
        reset()
        running = true
        val app = context.applicationContext
        scope.launch {
            try {
                val lines = runCatching { read(app, uri) }.getOrElse {
                    message = it.message ?: "Не удалось прочитать файл."
                    return@launch
                }
                if (lines.isEmpty()) {
                    message = "В файле не нашлось ни одной книги."
                    return@launch
                }
                total = lines.size
                val missing = mutableListOf<String>()
                for (l in lines) {
                    val book = runCatching { find(l) }.getOrNull()
                    when {
                        book == null -> missing += l.raw
                        ShelfRepository.get(book.id)?.status == ReadStatus.READ -> already++
                        else -> {
                            ShelfRepository.setStatus(book, ReadStatus.READ, knownDate = false)
                            added++
                        }
                    }
                    done++
                    notFound = missing.toList()
                    delay(120) // бережём FantLab
                }
                message = "Готово: отмечено прочитанными $added" +
                    (if (already > 0) ", уже были на полке $already" else "") +
                    (if (missing.isNotEmpty()) ", не найдено ${missing.size}" else "") + "."
                // найденным книгам в фоне подберём циклы и обложки
                ShelfRepository.repairSeriesInBackground()
            } finally {
                running = false
            }
        }
    }

    // ---------- чтение файла ----------

    private fun read(context: Context, uri: Uri): List<ImportLine> {
        val cr = context.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty().lowercase()
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл.")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isOldXls = bytes.size > 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()
        val rows: List<List<String>> = when {
            isZip || name.endsWith(".xlsx") -> xlsxRows(bytes)
            isOldXls || name.endsWith(".xls") ->
                throw IllegalStateException("Старый формат .xls не поддерживается. Сохраните таблицу как .xlsx или .csv.")
            else -> textRows(decode(bytes))
        }
        return rows.mapNotNull { cells ->
            val c = cells.map { it.trim() }.filter { it.isNotEmpty() }
            when {
                c.isEmpty() -> null
                c.size >= 2 -> ImportLine(c[0], c[1], c[0] + " — " + c[1])
                else -> ImportLine(null, null, c[0])
            }
        }.filterNot { l -> isHeader(l) }.distinctBy { it.raw.lowercase() }
    }

    private fun isHeader(l: ImportLine): Boolean {
        val a = l.author?.lowercase() ?: return false
        return a.startsWith("автор") || a == "author"
    }

    /** UTF-8, а если текст не похож на него — Windows-1251 (старые файлы из Блокнота/Excel). */
    private fun decode(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        return if (utf.contains('�')) String(bytes, charset("windows-1251")) else utf
    }

    private fun textRows(text: String): List<List<String>> = text.lines().map { line ->
        when {
            line.contains('\t') -> line.split('\t')
            line.contains(';') -> line.split(';')
            else -> listOf(line)
        }.map { it.trim().trim('"').trim() }
    }

    /** Простой разбор .xlsx: первый лист, общие строки и встроенные строки. */
    private fun xlsxRows(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = zip.readBytes()
            }
        }
        val shared = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { data ->
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(data), "UTF-8")
            var sb: StringBuilder? = null
            var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> sb = StringBuilder()
                        "t" -> inT = true
                    }
                    XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "si" -> { shared += sb?.toString().orEmpty(); sb = null }
                    }
                }
            }
        }
        val sheetName = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull {
            it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 999
        } ?: throw IllegalStateException("В таблице нет листов.")
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(files.getValue(sheetName)), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row: MutableMap<Int, String>? = null
        var col = 0
        var type: String? = null
        var value = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = sortedMapOf<Int, String>()
                    "c" -> {
                        col = colIndex(p.getAttributeValue(null, "r")) ?: ((row?.keys?.maxOrNull() ?: -1) + 1)
                        type = p.getAttributeValue(null, "t")
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (type == "s") raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty() else raw
                        row?.put(col, text)
                    }
                    "row" -> {
                        row?.let { r -> if (r.isNotEmpty()) rows += (0..(r.keys.maxOrNull() ?: 0)).map { r[it].orEmpty() } }
                        row = null
                    }
                }
            }
        }
        return rows
    }

    /** «B12» → 1. */
    private fun colIndex(ref: String?): Int? {
        val letters = ref?.takeWhile { it.isLetter() }?.uppercase() ?: return null
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    // ---------- поиск книги ----------

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private suspend fun find(l: ImportLine): Book? {
        if (l.title != null && l.author != null) {
            return search("${l.author} ${l.title}", l.title, norm(l.author))
                ?: search(l.title, l.title, norm(l.author))
                // столбцы могли перепутать местами
                ?: search(l.author, l.author, norm(l.title))
        }
        // «Автор Название»: сначала всей строкой, затем отделяем 1–3 первых слова как автора
        val line = l.raw
        search(line, null, norm(line))?.let { return it }
        val words = line.trim().split(Regex("\\s+"))
        for (k in 1..minOf(3, words.size - 1)) {
            val author = words.take(k).joinToString(" ")
            val title = words.drop(k).joinToString(" ")
            search(title, title, norm(author))?.let { return it }
        }
        return null
    }

    /**
     * Ищет книгу на FantLab. title — ожидаемое название (null — название должно входить в строку authorHint),
     * authorHint — нормализованный текст, в котором должна встречаться фамилия автора.
     */
    private suspend fun search(query: String, title: String?, authorHint: String): Book? {
        if (query.trim().length < 2) return null
        val found = FantLab.searchWorks(query, 1).items.filter { !it.isCycle && !isHiddenType(it.workType) }
        val want = title?.let { norm(it) }
        return found.firstOrNull { b ->
            val t = norm(b.title)
            val titleOk = if (want != null) t == want || (t.length >= 4 && want.startsWith(t)) || (want.length >= 4 && t.startsWith(want))
            else t.isNotEmpty() && (" $authorHint ").contains(" $t ")
            val authorOk = b.authors.isEmpty() || b.authors.any { a ->
                val surname = norm(a.name).split(' ').lastOrNull().orEmpty()
                surname.length >= 2 && authorHint.contains(surname)
            }
            titleOk && authorOk
        }
    }
}
