package ru.moistranicy.app.data.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Импорт книги в библиотеку читалки: файл копируется в files/books/<id>/,
 * FB2 превращается в главы HTML, EPUB распаковывается и читается как есть.
 */
object BookImporter {

    suspend fun import(context: Context, uri: Uri): LibBook = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val id = UUID.randomUUID().toString().take(12)
        val dir = Library.dirOf(id).apply { mkdirs() }
        try {
            val src = File(dir, "source.bin")
            context.contentResolver.openInputStream(uri)?.use { input -> src.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Файл не открылся")
            val book = if (isZip(src)) {
                ZipFile(src).use { z ->
                    if (z.getEntry("META-INF/container.xml") != null) Epub.convert(id, dir, z, name)
                    else {
                        // FB2 в архиве (.fb2.zip)
                        val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                            ?: throw IOException("В архиве нет книги FB2 или EPUB")
                        val fb2 = File(dir, "source.fb2")
                        z.getInputStream(e).use { i -> fb2.outputStream().use { i.copyTo(it) } }
                        Fb2.convert(id, dir, fb2, name)
                    }
                }
            } else Fb2.convert(id, dir, src, name)
            src.delete()
            Library.put(book)
            book
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException("Не удалось открыть книгу: это не FB2 и не EPUB или файл повреждён", e)
        }
    }

    private fun isZip(f: File): Boolean = f.inputStream().use { s ->
        val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Книга"
}

internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

/** Общие стили книг FB2 (настройки читателя накладываются поверх). */
private const val FB2_CSS = """
p { margin: 0; text-indent: 1.5em; }
p.e { text-indent: 0; height: 1em; }
.title { margin: 1.2em 0 0.8em; text-align: center; font-weight: bold; }
.title p { text-indent: 0; font-size: 1.15em; }
p.sub { text-indent: 0; text-align: center; font-weight: bold; margin: 0.8em 0; }
.epi { margin: 0.8em 0 0.8em 25%; font-style: italic; }
.epi p { text-indent: 0; }
blockquote { margin: 0.8em 1.5em; }
.poem { margin: 0.8em 0 0.8em 1.5em; }
.stanza { margin-bottom: 0.8em; }
p.v { text-indent: 0; }
p.ta { text-align: right; font-style: italic; text-indent: 0; }
img { display: block; margin: 0.6em auto; }
.book-title { text-align: center; margin: 2em 0; }
.book-title p { text-indent: 0; font-size: 1.4em; font-weight: bold; }
"""

private fun page(body: String) =
    "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width\"/><link rel=\"stylesheet\" href=\"book.css\"/></head><body>" +
        body + "</body></html>"

/** FB2 → главы HTML (верхние разделы книги), картинки — в img/. */
private object Fb2 {
    private const val MAX_CHAPTER = 120_000

    private fun parser(f: File): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // «мягкий» режим: не падаем на &nbsp; и прочих HTML-сущностях, которые встречаются в FB2
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(f.inputStream(), null)
    }

    private fun XmlPullParser.href(): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it).endsWith("href") }?.let { getAttributeValue(it) }

    fun convert(id: String, dir: File, file: File, fileName: String): LibBook {
        val img = File(dir, "img").apply { mkdirs() }
        // 1-й проход: картинки (они в конце файла)
        run {
            val p = parser(file)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "binary") {
                    val bid = p.getAttributeValue(null, "id")
                    val data = p.nextText()
                    if (bid != null) runCatching { File(img, safeName(bid)).writeBytes(Base64.decode(data, Base64.DEFAULT)) }
                }
                ev = p.next()
            }
        }
        // 2-й проход: описание и текст
        var title = ""
        var author = ""
        var series: String? = null
        var seriesNo: Int? = null
        var cover: String? = null
        val chapters = mutableListOf<Pair<String, String>>() // заголовок → html
        val p = parser(file)
        var inTitleInfo = false
        var authorDone = false
        val fio = arrayOf("", "", "")
        var inAuthor = false
        var inBody = false
        var notesBody = false
        var sectionDepth = 0
        var buf = StringBuilder()
        val prelude = StringBuilder()
        var chapTitle: StringBuilder? = null
        var chapTitleDone = false
        var titleDepth = 0
        var inTitle = 0

        fun out(): StringBuilder = if (inBody && sectionDepth == 0 && !notesBody) prelude else buf
        fun flush(t: String) {
            if (buf.isNotBlank()) chapters += t to buf.toString()
            buf = StringBuilder()
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    if (!inBody) {
                        when (n) {
                            "title-info" -> inTitleInfo = true
                            "book-title" -> if (inTitleInfo && title.isEmpty()) title = p.nextText().trim()
                            "author" -> if (inTitleInfo && !authorDone) inAuthor = true
                            "first-name" -> if (inAuthor) fio[0] = p.nextText().trim()
                            "middle-name" -> if (inAuthor) fio[1] = p.nextText().trim()
                            "last-name" -> if (inAuthor) fio[2] = p.nextText().trim()
                            "sequence" -> if (inTitleInfo && series == null) {
                                series = p.getAttributeValue(null, "name")?.trim()?.ifEmpty { null }
                                seriesNo = p.getAttributeValue(null, "number")?.trim()?.toIntOrNull()
                            }
                            "image" -> if (inTitleInfo && cover == null) p.href()?.removePrefix("#")?.let { cover = "img/" + safeName(it) }
                            "body" -> {
                                inBody = true
                                notesBody = p.getAttributeValue(null, "name")?.let { it == "notes" || it == "comments" } == true
                                if (notesBody) { flush("Глава ${chapters.size + 1}"); buf = StringBuilder() }
                            }
                        }
                    } else {
                        when (n) {
                            "section" -> {
                                sectionDepth++
                                if (sectionDepth == 1 && !notesBody) {
                                    chapTitle = StringBuilder(); chapTitleDone = false
                                } else out().append("<div class=\"sec\">")
                            }
                            "title" -> {
                                inTitle++; titleDepth = sectionDepth
                                out().append(if (sectionDepth == 0) "<div class=\"book-title\">" else "<div class=\"title\">")
                            }
                            "p" -> out().append(if (p.getAttributeValue(null, "style") != null) "<p>" else "<p>")
                            "subtitle" -> out().append("<p class=\"sub\">")
                            "emphasis" -> out().append("<em>")
                            "strong" -> out().append("<strong>")
                            "strikethrough" -> out().append("<s>")
                            "sup" -> out().append("<sup>")
                            "sub" -> out().append("<sub>")
                            "code" -> out().append("<code>")
                            "empty-line" -> out().append("<p class=\"e\">&nbsp;</p>")
                            "poem" -> out().append("<div class=\"poem\">")
                            "stanza" -> out().append("<div class=\"stanza\">")
                            "v" -> out().append("<p class=\"v\">")
                            "epigraph" -> out().append("<div class=\"epi\">")
                            "cite" -> out().append("<blockquote>")
                            "text-author" -> out().append("<p class=\"ta\">")
                            "image" -> p.href()?.removePrefix("#")?.let { out().append("<img src=\"img/${safeName(it)}\"/>") }
                            "table", "tr", "td", "th" -> out().append("<$n>")
                            "a" -> out().append("<span>")
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    if (!inBody) {
                        when (n) {
                            "title-info" -> inTitleInfo = false
                            "author" -> if (inAuthor) {
                                inAuthor = false; authorDone = true
                                author = listOf(fio[0], fio[1], fio[2]).filter { it.isNotEmpty() }.joinToString(" ")
                            }
                        }
                    } else {
                        when (n) {
                            "body" -> {
                                inBody = false
                                if (notesBody) { flush("Примечания"); notesBody = false }
                            }
                            "section" -> {
                                if (sectionDepth == 1 && !notesBody) {
                                    val t = chapTitle?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
                                    flush(t.ifEmpty { "Глава ${chapters.size + 1}" })
                                    chapTitle = null
                                } else out().append("</div>")
                                sectionDepth--
                            }
                            "title" -> {
                                inTitle--; out().append("</div>")
                                if (titleDepth == 1) chapTitleDone = true
                            }
                            "p", "subtitle", "v", "text-author" -> out().append("</p>")
                            "emphasis" -> out().append("</em>")
                            "strong" -> out().append("</strong>")
                            "strikethrough" -> out().append("</s>")
                            "sup" -> out().append("</sup>")
                            "sub" -> out().append("</sub>")
                            "code" -> out().append("</code>")
                            "poem", "stanza", "epigraph" -> out().append("</div>")
                            "cite" -> out().append("</blockquote>")
                            "table", "tr", "td", "th" -> out().append("</$n>")
                            "a" -> out().append("</span>")
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inBody) {
                    val t = p.text
                    out().append(esc(t))
                    if (inTitle > 0 && titleDepth == 1 && !chapTitleDone) chapTitle?.append(t)?.append(' ')
                }
            }
            ev = p.next()
        }
        if (buf.isNotBlank()) flush("Глава ${chapters.size + 1}")
        if (chapters.isEmpty() && prelude.isNotBlank()) { chapters += (title.ifEmpty { "Текст" }) to prelude.toString(); prelude.clear() }
        if (chapters.isEmpty()) throw IOException("В файле нет текста книги")

        File(dir, "book.css").writeText(FB2_CSS)
        val list = mutableListOf<Chapter>()
        chapters.forEachIndexed { i, (t, html0) ->
            val html = if (i == 0) prelude.toString() + html0 else html0
            // очень длинную главу режем на части по абзацам — так страницы листаются быстро
            val parts = splitHtml(html)
            parts.forEachIndexed { k, part ->
                val path = "c%04d_%02d.html".format(i, k)
                File(dir, path).writeText(page(part))
                list += Chapter(path, if (k == 0) t else "$t (продолжение)", part.length.toLong())
            }
        }
        file.delete()
        return LibBook(
            id = id,
            title = title.ifEmpty { fileName.substringBeforeLast('.').substringBeforeLast(".fb2") },
            author = author,
            series = series,
            seriesNo = seriesNo,
            format = "FB2",
            chapters = list,
            cover = cover?.takeIf { File(dir, it).exists() },
        )
    }

    private fun splitHtml(html: String): List<String> {
        if (html.length <= MAX_CHAPTER) return listOf(html)
        val out = mutableListOf<String>()
        var start = 0
        while (start < html.length) {
            if (html.length - start <= MAX_CHAPTER) { out += html.substring(start); break }
            var cut = html.indexOf("</p>", start + MAX_CHAPTER)
            // режем только на верхнем уровне: если внутри открыт блок, ищем дальше (не больше чем вдвое)
            if (cut < 0 || cut > start + MAX_CHAPTER * 2) { out += html.substring(start); break }
            cut += 4
            out += html.substring(start, cut)
            start = cut
        }
        return out
    }
}

/** EPUB: распаковываем, главы — файлы из spine, названия — из оглавления. */
private object Epub {
    private fun local(n: String) = n.substringAfter(':')

    private fun parser(f: File): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(f.inputStream(), null)
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { local(getAttributeName(it)) == name }?.let { getAttributeValue(it) }

    private fun resolve(base: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = (if (base.isEmpty()) clean else "$base/$clean").split('/').fold(ArrayDeque<String>()) { acc, s ->
            when (s) { "", "." -> {}; ".." -> acc.removeLastOrNull(); else -> acc.addLast(s) }
            acc
        }
        return parts.joinToString("/")
    }

    fun convert(id: String, dir: File, zip: ZipFile, fileName: String): LibBook {
        val root = File(dir, "epub").apply { mkdirs() }
        val rootPath = root.canonicalPath + File.separator
        for (e in zip.entries()) {
            val out = File(root, e.name)
            if (!out.canonicalPath.startsWith(rootPath)) continue // защита от «../» в архиве
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            zip.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        // container.xml → путь к OPF
        var opfPath: String? = null
        parser(File(root, "META-INF/container.xml")).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "rootfile") opfPath = p.attr("full-path")
                ev = p.next()
            }
        }
        val opf = opfPath ?: throw IOException("Повреждённый EPUB: нет описания книги")
        val base = opf.substringBeforeLast('/', "")
        var title = ""
        var author = ""
        var series: String? = null
        var seriesNo: Int? = null
        var coverId: String? = null
        var coverHref: String? = null
        var tocId: String? = null
        var navHref: String? = null
        val manifest = HashMap<String, Pair<String, String>>() // id → (href, media-type)
        val spine = mutableListOf<String>()
        parser(File(root, opf)).let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "title" -> if (title.isEmpty()) title = p.nextText().trim()
                        "creator" -> if (author.isEmpty()) author = p.nextText().trim()
                        "meta" -> {
                            val nm = p.attr("name")
                            when (nm) {
                                "calibre:series" -> series = p.attr("content")?.ifBlank { null }
                                "calibre:series_index" -> seriesNo = p.attr("content")?.toDoubleOrNull()?.toInt()
                                "cover" -> coverId = p.attr("content")
                            }
                        }
                        "item" -> {
                            val iid = p.attr("id"); val href = p.attr("href")
                            if (iid != null && href != null) {
                                manifest[iid] = href to (p.attr("media-type") ?: "")
                                val props = p.attr("properties").orEmpty()
                                if ("cover-image" in props) coverHref = href
                                if ("nav" in props.split(' ')) navHref = href
                            }
                        }
                        "spine" -> tocId = p.attr("toc")
                        "itemref" -> p.attr("idref")?.let { spine += it }
                    }
                }
                ev = p.next()
            }
        }
        // оглавление: файл → название (NCX или навигация EPUB 3)
        val titles = HashMap<String, String>()
        val tocHref = tocId?.let { manifest[it]?.first } ?: navHref
        if (tocHref != null) runCatching {
            val tocPath = resolve(base, tocHref)
            val tocBase = tocPath.substringBeforeLast('/', "")
            val p = parser(File(root, tocPath))
            var label: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                    "text" -> label = p.nextText().trim()
                    "content" -> p.attr("src")?.let { s -> val k = resolve(tocBase, s); if (k !in titles && !label.isNullOrBlank()) titles[k] = label!! }
                    "a" -> {
                        val href = p.attr("href")
                        val t = runCatching { p.nextText().trim() }.getOrNull()
                        if (href != null && !t.isNullOrBlank()) { val k = resolve(tocBase, href); if (k !in titles) titles[k] = t }
                    }
                }
                ev = p.next()
            }
        }
        val chapters = spine.mapNotNull { manifest[it] }
            .filter { (_, mt) -> mt.contains("html") }
            .mapIndexedNotNull { i, (href, _) ->
                val path = resolve(base, href)
                val f = File(root, path)
                if (!f.exists()) null
                else Chapter("epub/$path", titles[path] ?: "Глава ${i + 1}", f.length().coerceAtLeast(1))
            }
        if (chapters.isEmpty()) throw IOException("В EPUB нет глав")
        val cover = (coverHref ?: coverId?.let { manifest[it]?.first })?.let { "epub/" + resolve(base, it) }
            ?.takeIf { File(dir, it).exists() }
        File(dir, "source.bin").delete()
        return LibBook(
            id = id,
            title = title.ifEmpty { fileName.substringBeforeLast('.') },
            author = author,
            series = series,
            seriesNo = seriesNo,
            format = "EPUB",
            chapters = chapters,
            cover = cover,
        )
    }
}
