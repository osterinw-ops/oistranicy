package ru.moistranicy.app.data

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface OpdsEntry {
    data class BookEntry(val book: Book) : OpdsEntry
    data class NavEntry(val title: String, val href: String, val subtitle: String) : OpdsEntry
}

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>, val next: String?)

/**
 * Каталог книг через OPDS (Flibusta и совместимые зеркала).
 * Берём только описания: название, автор, цикл, аннотация, обложка.
 */
object OpdsClient {

    fun searchBooksPath(query: String) =
        "/opds/search?searchType=books&searchTerm=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun searchAuthorsPath(query: String) =
        "/opds/search?searchType=authors&searchTerm=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun seriesPath(id: String) = "/opds/sequencebooks/$id"
    fun authorPath(id: String) = "/opds/author/$id"

    fun absolute(href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val base = AppPrefs.baseUrl.trimEnd('/')
        return if (href.startsWith("/")) base + href else "$base/$href"
    }

    suspend fun fetch(path: String): OpdsFeed = withContext(Dispatchers.IO) {
        val conn = URL(absolute(path)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MoiStranicy/0.1 (Android)")
        conn.setRequestProperty("Accept", "application/atom+xml, application/xml, text/xml")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Сервер ответил кодом $code")
            conn.inputStream.use { parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- парсер ----------------

    private class RawLink(val href: String, val rel: String, val type: String, val title: String)

    private class RawEntry {
        var title = ""
        val authors = mutableListOf<Pair<String, String?>>()
        val links = mutableListOf<RawLink>()
        var content = ""
        val categories = mutableListOf<String>()
        var issued: String? = null
        var language: String? = null
    }

    private val BOOK_ID = Regex("^/b/(\\d+)")
    private val SERIES_ID = Regex("/opds/sequencebooks/(\\d+)")
    private val AUTHOR_OPDS_ID = Regex("/opds/author/(\\d+)")
    private val AUTHOR_URI_ID = Regex("/a/(\\d+)")
    private val QUOTED = Regex("«(.+)»")
    private val YEAR = Regex("(1[5-9]\\d\\d|20\\d\\d)")
    private val SERIES_LINE = Regex("^Серия:\\s*(.+?)(?:\\s*#\\s*(\\d+))?\\s*$")
    private val SKIP_LINE = Regex("^(Формат|Размер|Скачиваний|Язык|Год издания|Серия|Перевод|Добавлена|Лицензия):")

    fun parse(input: InputStream): OpdsFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        val entries = mutableListOf<OpdsEntry>()
        var feedTitle = ""
        var next: String? = null
        var cur: RawEntry? = null
        var inAuthor = false
        var authorName = ""
        var authorUri: String? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "entry" -> cur = RawEntry()
                    "author" -> if (cur != null) { inAuthor = true; authorName = ""; authorUri = null }
                    "name" -> if (inAuthor) authorName = readText(p).trim()
                    "uri" -> if (inAuthor) authorUri = readText(p).trim()
                    "title" -> {
                        val t = readText(p).trim()
                        if (cur != null) cur.title = t else if (feedTitle.isEmpty()) feedTitle = t
                    }
                    "content" -> cur?.content = readText(p)
                    "category" -> cur?.let { c ->
                        val label = p.getAttributeValue(null, "label") ?: p.getAttributeValue(null, "term")
                        if (!label.isNullOrBlank()) c.categories.add(label.trim())
                    }
                    "dc:issued" -> cur?.issued = readText(p).trim()
                    "dc:language" -> cur?.language = readText(p).trim()
                    "link" -> {
                        val link = RawLink(
                            href = p.getAttributeValue(null, "href") ?: "",
                            rel = p.getAttributeValue(null, "rel") ?: "",
                            type = p.getAttributeValue(null, "type") ?: "",
                            title = p.getAttributeValue(null, "title") ?: "",
                        )
                        if (cur != null) cur.links.add(link) else if (link.rel == "next") next = link.href
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "author" -> if (inAuthor) {
                        if (authorName.isNotBlank()) cur?.authors?.add(authorName to authorUri)
                        inAuthor = false
                    }
                    "entry" -> {
                        cur?.let { raw -> toEntry(raw)?.let { entries.add(it) } }
                        cur = null
                    }
                }
            }
            ev = p.next()
        }
        return OpdsFeed(feedTitle, entries, next)
    }

    /** Читает весь текст внутри текущего тега, включая вложенные теги. */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == depth) && e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.TEXT) sb.append(p.text)
            e = p.next()
        }
        return sb.toString()
    }

    private fun htmlToText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun toEntry(raw: RawEntry): OpdsEntry? {
        val bookId = raw.links.firstNotNullOfOrNull { BOOK_ID.find(it.href)?.groupValues?.get(1) }
        if (bookId == null) {
            val nav = raw.links.firstOrNull { it.type.contains("atom") || it.href.startsWith("/opds") }
                ?: return null
            if (raw.title.isBlank()) return null
            return OpdsEntry.NavEntry(raw.title, nav.href, htmlToText(raw.content).trim())
        }

        // Аннотация и служебные строки из content
        val lines = htmlToText(raw.content).lines().map { it.trim() }
        var seriesNameFromText: String? = null
        var seriesNumber: Int? = null
        var yearFromText: String? = null
        val annotation = StringBuilder()
        for (line in lines) {
            val s = SERIES_LINE.find(line)
            when {
                s != null -> {
                    seriesNameFromText = s.groupValues[1].trim()
                    seriesNumber = s.groupValues.getOrNull(2)?.toIntOrNull()
                }
                line.startsWith("Год издания:") -> yearFromText = YEAR.find(line)?.value
                SKIP_LINE.containsMatchIn(line) -> Unit
                line.isEmpty() -> if (annotation.isNotEmpty() && !annotation.endsWith("\n\n")) annotation.append("\n\n")
                else -> { if (annotation.isNotEmpty() && !annotation.endsWith("\n")) annotation.append(' '); annotation.append(line) }
            }
        }

        val cover = raw.links.firstOrNull { it.rel.endsWith("/image") || it.rel == "x-stanza-cover-image" }?.href
            ?: raw.links.firstOrNull { it.rel.contains("image") || it.rel.contains("thumbnail") }?.href

        val seriesLink = raw.links.firstOrNull { SERIES_ID.containsMatchIn(it.href) }
        val seriesId = seriesLink?.let { SERIES_ID.find(it.href)?.groupValues?.get(1) }
        val seriesName = seriesLink?.let { QUOTED.find(it.title)?.groupValues?.get(1) } ?: seriesNameFromText

        val authorLinks = raw.links.filter { AUTHOR_OPDS_ID.containsMatchIn(it.href) }
        val authors = raw.authors.mapIndexed { i, (name, uri) ->
            val id = uri?.let { AUTHOR_URI_ID.find(it)?.groupValues?.get(1) }
                ?: authorLinks.firstOrNull { it.title.contains(name) }?.let { AUTHOR_OPDS_ID.find(it.href)?.groupValues?.get(1) }
                ?: if (raw.authors.size == authorLinks.size) AUTHOR_OPDS_ID.find(authorLinks[i].href)?.groupValues?.get(1) else null
            Person(id, name)
        }

        val book = Book(
            id = bookId,
            title = raw.title.ifBlank { "Без названия" },
            authors = authors,
            coverUrl = cover,
            annotation = annotation.toString().trim(),
            genres = raw.categories.distinct(),
            year = raw.issued?.let { YEAR.find(it)?.value } ?: yearFromText,
            language = raw.language,
            seriesId = seriesId,
            seriesName = seriesName,
            seriesNumber = seriesNumber,
        )
        BookCache.put(book)
        return OpdsEntry.BookEntry(book)
    }
}
