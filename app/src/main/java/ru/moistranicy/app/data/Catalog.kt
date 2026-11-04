package ru.moistranicy.app.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Page<T>(val items: List<T>, val hasMore: Boolean)

private object Http {
    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("User-Agent", "MoiStranicy/0.2 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Сервер ответил кодом $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

private val BB_TAG = Regex("\\[/?[a-zA-Z*]+(=[^\\]]*)?\\]")

/** Убирает HTML и bb-теги, нормализует переносы строк. */
internal fun cleanText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val noBb = raw.replace(BB_TAG, "")
    val text = if (noBb.contains('<')) Html.fromHtml(noBb, Html.FROM_HTML_MODE_LEGACY).toString() else noBb
    return text.replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun JSONObject.str(key: String): String =
    if (has(key) && !isNull(key)) optString(key).trim() else ""

private fun JSONObject.posInt(key: String): Int? = optInt(key, 0).takeIf { it > 0 }

private fun JSONObject.floatOrNull(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    val d = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        is JSONArray -> v.optDouble(0)
        is JSONObject -> v.optDouble("rating")
        else -> null
    }
    return d?.takeIf { !it.isNaN() && it > 0 }?.toFloat()
}

/** FantLab — открытая библиографическая база (https://fantlab.ru). */
object FantLab {
    private const val API = "https://api.fantlab.ru"
    private const val SITE = "https://fantlab.ru"
    private const val PAGE_SIZE = 25
    private val CYCLE_TYPES = setOf("cycle", "epic", "series")

    fun image(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> SITE + path
            else -> "$SITE/$path"
        }
    }

    private fun editionCover(id: Int) = "$SITE/images/editions/big/$id"

    suspend fun searchWorks(query: String, page: Int): Page<Book> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Http.get("$API/search-works?q=${enc(query)}&page=$page&onlymatches=1"))
        val books = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val workId = o.posInt("work_id") ?: return@mapNotNull null
            val authors = (1..5).mapNotNull { n ->
                val id = o.posInt("autor${n}_id") ?: return@mapNotNull null
                val name = o.str("autor${n}_rusname")
                if (name.isEmpty()) null else Person("fl-$id", name)
            }.ifEmpty {
                o.str("all_autor_rusname").ifEmpty { o.str("all_autor_name") }
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { Person(null, cleanText(it)) }
            }
            val cover = (o.posInt("pic_edition_id") ?: o.posInt("pic_edition_id_auto"))?.let { editionCover(it) }
            Book(
                id = "fl-$workId",
                title = o.str("rusname").ifEmpty { o.str("name") }.ifEmpty { "Без названия" },
                authors = authors,
                coverUrl = cover,
                annotation = "",
                genres = emptyList(),
                year = o.posInt("year")?.toString(),
                language = null,
                seriesId = null,
                seriesName = null,
                seriesNumber = null,
                workType = o.str("name_show_im").ifEmpty { null },
                isCycle = o.str("name_eng") in CYCLE_TYPES,
                communityRating = o.floatOrNull("midmark_by_weight") ?: o.floatOrNull("midmark"),
            ).also { BookCache.put(it) }
        }
        Page(books, arr.length() >= PAGE_SIZE)
    }

    suspend fun searchAuthors(query: String, page: Int): Page<AuthorHit> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Http.get("$API/search-autors?q=${enc(query)}&page=$page&onlymatches=1"))
        val hits = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.posInt("autor_id") ?: return@mapNotNull null
            val name = o.str("rusname").ifEmpty { o.str("name") }
            val years = listOfNotNull(o.posInt("birthyear"), o.posInt("deathyear")).joinToString("–")
            val sub = listOf(o.str("name").takeIf { it != name }.orEmpty(), o.str("country"), years)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            AuthorHit("fl-$id", name, sub)
        }
        Page(hits, arr.length() >= PAGE_SIZE)
    }

    suspend fun work(id: String): WorkDetails = withContext(Dispatchers.IO) {
        val num = id.removePrefix("fl-")
        val o = JSONObject(Http.get("$API/work/$num?children=1&parents=1&classificatory=1"))

        val parents = mutableListOf<SeriesRef>()
        o.optJSONObject("parents")?.optJSONArray("cycles")?.let { cycles ->
            for (i in 0 until cycles.length()) {
                val chain = cycles.optJSONArray(i) ?: continue
                for (j in 0 until chain.length()) {
                    val c = chain.optJSONObject(j) ?: continue
                    val cid = c.posInt("work_id") ?: continue
                    val ref = SeriesRef("fl-$cid", c.str("work_name").ifEmpty { c.str("work_name_orig") })
                    if (parents.none { it.id == ref.id } && ref.name.isNotEmpty()) parents.add(ref)
                }
            }
        }
        // ближайший цикл: последний элемент первой цепочки с типом «цикл»
        // (роман-эпопея внутри цикла слишком мелкая единица для отслеживания)
        val nearest = o.optJSONObject("parents")?.optJSONArray("cycles")?.optJSONArray(0)?.let { chain ->
            val elems = (0 until chain.length()).mapNotNull { chain.optJSONObject(it) }
            elems.lastOrNull { it.str("work_type_name") == "cycle" || it.str("work_type").equals("цикл", true) }
                ?: elems.lastOrNull()
        }

        val authors = o.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = a.str("name").ifEmpty { a.str("name_orig") }
                if (name.isEmpty()) null
                else Person(a.posInt("id")?.takeIf { a.str("type") == "autor" }?.let { "fl-$it" }, name)
            }
        }.orEmpty()

        val book = Book(
            id = "fl-$num",
            title = o.str("work_name").ifEmpty { o.str("work_name_orig") }.ifEmpty { "Без названия" },
            authors = authors,
            coverUrl = image(o.str("image")),
            annotation = cleanText(o.str("work_description")),
            genres = genres(o),
            year = o.posInt("work_year")?.toString(),
            language = o.str("lang").ifEmpty { null },
            seriesId = nearest?.posInt("work_id")?.let { "fl-$it" },
            seriesName = nearest?.str("work_name")?.ifEmpty { null },
            seriesNumber = null,
            workType = o.str("work_type").ifEmpty { null },
            isCycle = o.str("work_type_name") in CYCLE_TYPES,
            communityRating = o.floatOrNull("rating"),
        )
        BookCache.put(book)

        val children = o.optJSONArray("children")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val b = blockItemToBook(c) ?: return@mapNotNull null
                CycleItem(b, c.optInt("deep", 1).coerceAtLeast(1), b.published)
            }
        }.orEmpty()

        WorkDetails(book, parents, children)
    }

    suspend fun author(id: String): AuthorDetails = withContext(Dispatchers.IO) {
        val num = id.removePrefix("fl-")
        val o = JSONObject(Http.get("$API/autor/$num?biblio_blocks=1"))
        val sections = mutableListOf<AuthorSection>()
        sections += cycleSections(o.opt("cycles_blocks"))
        sections += blocks(o.opt("works_blocks"))
        val years = listOf(o.str("birthday"), o.str("deathday"))
            .map { it.take(4) }.filter { it.length == 4 && it != "0000" }.joinToString("–")
        AuthorDetails(
            id = "fl-$num",
            name = o.str("name").ifEmpty { o.str("name_orig") },
            nameOrig = o.str("name_orig"),
            imageUrl = image(o.str("image")),
            years = years,
            sections = sections.filter { it.books.isNotEmpty() },
        )
    }

    private fun blockObjects(raw: Any?): List<JSONObject> {
        val list = mutableListOf<Pair<Int, JSONObject>>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONObject(k)?.let { list += (k.toIntOrNull() ?: 999) to it } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { list += it.optInt("id", i) to it }
        }
        return list.sortedBy { it.first }.map { it.second }
    }

    /** Каждый цикл автора — отдельный раздел с книгами по порядку. */
    private fun cycleSections(raw: Any?): List<AuthorSection> {
        val result = mutableListOf<AuthorSection>()
        val seen = HashSet<String>()
        for (block in blockObjects(raw)) {
            val items = block.optJSONArray("list") ?: continue
            for (i in 0 until items.length()) {
                val cyc = items.optJSONObject(i) ?: continue
                val cycleBook = blockItemToBook(cyc) ?: continue
                if (!seen.add(cycleBook.id)) continue
                val ch = cyc.optJSONArray("children")
                val children = if (ch == null) emptyList() else (0 until ch.length()).mapNotNull { j ->
                    val c = ch.optJSONObject(j) ?: return@mapNotNull null
                    val b = blockItemToBook(c) ?: return@mapNotNull null
                    CycleItem(b, c.optInt("deep", 1).coerceAtLeast(1), b.published)
                }
                val books = cycleBooks(children).map { it.book }
                if (books.isEmpty()) {
                    // цикл без раскрытого состава — показываем его как ссылку
                    result += AuthorSection(cycleBook.title, listOf(cycleBook), null)
                } else {
                    result += AuthorSection(cycleBook.title, books, cycleBook.id)
                }
            }
        }
        // одиночные «циклы-ссылки» собираем в один раздел в конце
        val (cycles, singles) = result.partition { it.cycleId != null }
        return if (singles.isEmpty()) cycles
        else cycles + AuthorSection("Другие циклы", singles.flatMap { it.books })
    }

    private fun blocks(raw: Any?): List<AuthorSection> {
        val list = mutableListOf<Pair<Int, JSONObject>>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONObject(k)?.let { list += (k.toIntOrNull() ?: 999) to it } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { list += it.optInt("id", i) to it }
        }
        // «Планируется к публикации» (id 0) — в конец
        return list.sortedBy { (k, _) -> if (k == 0) Int.MAX_VALUE else k }.map { (_, b) ->
            val items = b.optJSONArray("list")
            val books = if (items == null) emptyList() else
                (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { blockItemToBook(it) } }
            AuthorSection(b.str("title").ifEmpty { b.str("name") }, books)
        }
    }

    private fun blockItemToBook(c: JSONObject): Book? {
        val wid = c.posInt("work_id") ?: return null
        val title = c.str("work_name").ifEmpty { c.str("work_name_orig") }.ifEmpty { return null }
        val authors = c.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = a.str("name")
                if (name.isEmpty()) null else Person(a.posInt("id")?.let { "fl-$it" }, name)
            }
        }.orEmpty()
        val book = Book(
            id = "fl-$wid",
            title = title,
            authors = authors,
            coverUrl = null,
            annotation = cleanText(c.str("work_description")),
            genres = emptyList(),
            year = c.posInt("work_year")?.toString(),
            language = null,
            seriesId = null,
            seriesName = null,
            seriesNumber = null,
            workType = c.str("work_type").ifEmpty { null },
            isCycle = c.str("work_type_name") in CYCLE_TYPES,
            communityRating = c.floatOrNull("val_midmark_by_weight") ?: c.floatOrNull("rating"),
            published = c.optInt("work_published", 1) == 1 && c.optInt("work_preparing", 0) == 0,
        )
        BookCache.put(book)
        return (BookCache.get(book.id) ?: book).copy(published = book.published)
    }

    private fun genres(o: JSONObject): List<String> {
        val groups = o.optJSONObject("classificatory")?.optJSONArray("genre_group") ?: return emptyList()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (!g.str("label").startsWith("Жанр")) continue
            val arr = g.optJSONArray("genre") ?: continue
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .sortedByDescending { it.optInt("votes") }
                .map { it.str("label") }.filter { it.isNotEmpty() }.take(4)
        }
        return emptyList()
    }
}

/** Google Книги — для книг, которых нет на FantLab (нон-фикшн, классика и т. п.). */
object GoogleBooks {
    private const val PAGE_SIZE = 20

    suspend fun search(query: String, page: Int): Page<Book> = withContext(Dispatchers.IO) {
        val start = (page - 1) * PAGE_SIZE
        val o = JSONObject(
            Http.get(
                "https://www.googleapis.com/books/v1/volumes?q=${enc(query)}" +
                    "&printType=books&maxResults=$PAGE_SIZE&startIndex=$start&hl=ru",
            ),
        )
        val items = o.optJSONArray("items") ?: JSONArray()
        val books = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.str("id").ifEmpty { return@mapNotNull null }
            val v = item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            val authors = v.optJSONArray("authors")?.let { a ->
                (0 until a.length()).map { k -> Person(null, a.optString(k)) }
            }.orEmpty()
            val img = v.optJSONObject("imageLinks")?.let { l ->
                l.str("thumbnail").ifEmpty { l.str("smallThumbnail") }
            }?.ifEmpty { null }?.replace("http://", "https://")?.replace("&edge=curl", "")
            val title = listOf(v.str("title"), v.str("subtitle")).filter { s -> s.isNotEmpty() }.joinToString(". ")
            Book(
                id = "gb-$id",
                title = title.ifEmpty { "Без названия" },
                authors = authors,
                coverUrl = img,
                annotation = cleanText(v.str("description")),
                genres = v.optJSONArray("categories")?.let { c -> (0 until c.length()).map { k -> c.optString(k) } }.orEmpty(),
                year = v.str("publishedDate").take(4).ifEmpty { null },
                language = v.str("language").ifEmpty { null },
                seriesId = null,
                seriesName = null,
                seriesNumber = null,
                workType = null,
                isCycle = false,
                communityRating = v.floatOrNull("averageRating")?.let { r -> r * 2 },
            ).also { b -> BookCache.put(b) }
        }
        val total = o.optInt("totalItems", 0)
        Page(books, books.isNotEmpty() && start + PAGE_SIZE < total)
    }
}
