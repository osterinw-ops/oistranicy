package ru.moistranicy.app.data.reader

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ссылка из OPDS: rel, адрес (уже абсолютный) и тип. */
data class OpdsLink(val rel: String, val href: String, val type: String)

/** Запись каталога: раздел (ведёт в другую ленту) или книга (есть ссылки на скачивание). */
data class OpdsEntry(
    val title: String,
    val author: String,
    val summary: String,
    val links: List<OpdsLink>,
) {
    /** Ссылка на вложенную ленту (раздел каталога). */
    val navigation: OpdsLink? get() = links.firstOrNull { it.type.contains("atom+xml") && !it.rel.contains("acquisition") }
        ?: links.firstOrNull { it.type.contains("opds-catalog") }

    /** Файлы книги, которые умеет открыть читалка (FB2, FB2.ZIP, EPUB), — лучшие первыми. */
    val downloads: List<OpdsLink> get() = links
        .filter { it.rel.contains("acquisition") || it.type.contains("fb2") || it.type.contains("epub") }
        .filter { Opds.formatOf(it) != null }
        .sortedBy { listOf("FB2", "FB2.ZIP", "EPUB").indexOf(Opds.formatOf(it)) }

    val cover: String? get() = links.firstOrNull { it.rel.contains("thumbnail") }?.href
        ?: links.firstOrNull { it.rel.contains("opds-spec.org/image") || it.rel.contains("cover") }?.href
}

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val next: String?,
    /** Шаблон поиска с {searchTerms}, если каталог его даёт. */
    val searchTemplate: String?,
)

/** Сохранённый каталог: адрес и (необязательно) логин с паролем. */
data class OpdsCatalog(val title: String, val url: String, val user: String = "", val password: String = "")

/**
 * OPDS-каталоги: только форма, без готовых ссылок. Пользователь сам вводит адрес своего каталога
 * (домашняя библиотека, Calibre, сервер книжного магазина), мы читаем Atom-ленты и скачиваем FB2/EPUB в читалку.
 */
object Opds {
    private lateinit var store: File

    fun init(context: Context) {
        store = File(context.filesDir, "opds.json")
    }

    fun catalogs(): List<OpdsCatalog> = runCatching {
        val a = JSONArray(store.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            OpdsCatalog(o.optString("title"), o.getString("url"), o.optString("user"), o.optString("password"))
        }
    }.getOrDefault(emptyList())

    fun saveCatalogs(list: List<OpdsCatalog>) {
        val a = JSONArray()
        list.forEach { c -> a.put(JSONObject().put("title", c.title).put("url", c.url).put("user", c.user).put("password", c.password)) }
        runCatching { store.writeText(a.toString()) }
    }

    fun formatOf(l: OpdsLink): String? {
        val t = l.type.lowercase(); val h = l.href.lowercase().substringBefore('?')
        return when {
            t.contains("fb2+zip") || t.contains("fb2.zip") || h.endsWith(".fb2.zip") || (h.contains("/fb2") && t.contains("zip")) -> "FB2.ZIP"
            t.contains("fb2") || h.endsWith(".fb2") -> "FB2"
            t.contains("epub") || h.endsWith(".epub") -> "EPUB"
            else -> null
        }
    }

    private fun open(url: String, cat: OpdsCatalog): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MoiStranicy/1.0 (Android)")
            if (cat.user.isNotEmpty()) {
                val token = Base64.encodeToString("${cat.user}:${cat.password}".toByteArray(), Base64.NO_WRAP)
                c.setRequestProperty("Authorization", "Basic $token")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Каталог перенаправил без адреса")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code == 401 || code == 403) throw IOException("Каталог требует логин и пароль (или они неверны)")
            if (code !in 200..299) throw IOException("Каталог ответил ошибкой $code")
            return c
        }
        throw IOException("Слишком много перенаправлений")
    }

    suspend fun load(url: String, cat: OpdsCatalog): OpdsFeed = withContext(Dispatchers.IO) {
        val c = open(url, cat)
        try {
            val real = c.url.toString()
            val feed = c.inputStream.use { parse(it, real) }
            // ссылка поиска может вести на описание OpenSearch — достаём из него шаблон
            val search = feed.searchTemplate?.let { s ->
                if (s.contains("{searchTerms}")) s else runCatching { openSearchTemplate(s, cat) }.getOrNull()
            }
            feed.copy(searchTemplate = search)
        } finally {
            c.disconnect()
        }
    }

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query, "UTF-8"))
            .replace(Regex("\\{[^}]*\\?}"), "")

    private fun openSearchTemplate(url: String, cat: OpdsCatalog): String? {
        val c = open(url, cat)
        try {
            val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            c.inputStream.use { input ->
                p.setInput(input, null)
                var best: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Url") {
                        val t = p.getAttributeValue(null, "type").orEmpty()
                        val tpl = p.getAttributeValue(null, "template") ?: continue
                        val abs = URL(URL(c.url.toString()), tpl).toString().replace("%7B", "{").replace("%7D", "}")
                        if (t.contains("atom") || best == null) best = abs
                    }
                }
                return best
            }
        } finally {
            c.disconnect()
        }
    }

    /** Разбор Atom-ленты OPDS. */
    private fun parse(input: InputStream, base: String): OpdsFeed {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(input, null)
        fun abs(h: String) = runCatching { URL(URL(base), h).toString() }.getOrDefault(h)

        var feedTitle = ""
        var next: String? = null
        var search: String? = null
        val entries = mutableListOf<OpdsEntry>()

        var inEntry = false
        var inAuthor = false
        var title = ""; var author = StringBuilder(); var summary = ""
        var links = mutableListOf<OpdsLink>()
        var text = StringBuilder()
        var depthText = false

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (!depthText) text = StringBuilder()
                    when (p.name) {
                        "entry" -> { inEntry = true; title = ""; author = StringBuilder(); summary = ""; links = mutableListOf() }
                        "author" -> inAuthor = true
                        "link" -> {
                            val rel = p.getAttributeValue(null, "rel").orEmpty()
                            val href = p.getAttributeValue(null, "href") ?: ""
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            if (href.isNotEmpty()) {
                                if (inEntry) links += OpdsLink(rel, abs(href), type)
                                else when {
                                    rel == "next" -> next = abs(href)
                                    rel == "search" && (search == null || type.contains("atom")) -> search = abs(href)
                                }
                            }
                        }
                        "content", "summary" -> depthText = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "title" -> if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t
                        "name" -> if (inEntry && inAuthor && t.isNotEmpty()) { if (author.isNotEmpty()) author.append(", "); author.append(t) }
                        "author" -> inAuthor = false
                        "content", "summary" -> {
                            if (inEntry && summary.isEmpty()) summary = t.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                            depthText = false
                        }
                        "entry" -> {
                            inEntry = false
                            entries += OpdsEntry(title.ifEmpty { "Без названия" }, author.toString(), summary, links.toList())
                        }
                    }
                    if (!depthText) text = StringBuilder()
                }
            }
        }
        return OpdsFeed(base, feedTitle, entries, next, search)
    }

    /** Скачать файл книги во временную папку; дальше его импортирует читалка. */
    suspend fun download(context: Context, link: OpdsLink, cat: OpdsCatalog): File = withContext(Dispatchers.IO) {
        val c = open(link.href, cat)
        try {
            val ext = when (formatOf(link)) { "EPUB" -> "epub"; "FB2.ZIP" -> "fb2.zip"; else -> "fb2" }
            val dir = File(context.cacheDir, "opds").apply { mkdirs() }
            val f = File(dir, "book_${System.currentTimeMillis()}.$ext")
            c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
            f
        } finally {
            c.disconnect()
        }
    }
}
