package ru.moistranicy.app.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            ).also { BookCache.put(it); KnownAuthors.addAll(it.authors) }
        }.filter { !isHiddenType(it.workType) }
        Page(books, arr.length() >= PAGE_SIZE)
    }

    suspend fun searchAuthors(query: String, page: Int): Page<AuthorHit> = withContext(Dispatchers.IO) {
        val direct = runCatching { searchAuthorsDirect(query, page) }
        val found = direct.getOrNull()
        if (page > 1 || (found != null && found.items.isNotEmpty())) return@withContext direct.getOrThrow()
        // поиск авторов FantLab находит не всех: ищем автора среди авторов найденных книг
        val fromWorks = runCatching { authorsFromWorks(query) }.getOrDefault(emptyList())
        if (fromWorks.isEmpty()) direct.getOrThrow() else Page(fromWorks, false)
    }

    private fun searchAuthorsDirect(query: String, page: Int): Page<AuthorHit> {
        val arr = JSONArray(Http.get("$API/search-autors?q=${enc(query)}&page=$page&onlymatches=1"))
        val hits = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.posInt("autor_id") ?: return@mapNotNull null
            val name = o.str("rusname").ifEmpty { o.str("name") }
            val years = listOfNotNull(o.posInt("birthyear"), o.posInt("deathyear")).joinToString("–")
            val sub = listOf(o.str("name").takeIf { it != name }.orEmpty(), o.str("country"), years)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            KnownAuthors.add(name, "fl-$id")
            AuthorHit("fl-$id", name, sub)
        }
        return Page(hits, arr.length() >= PAGE_SIZE)
    }

    private suspend fun authorsFromWorks(query: String): List<AuthorHit> {
        fun norm(s: String) = s.lowercase().replace('ё', 'е')
        val words = norm(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun matches(p: Person) = norm(p.name).let { name -> words.all { it in name } }
        fun usable(p: Person) = p.id?.startsWith("fl-") == true && matches(p)
        val books = searchWorks(query, 1).items
        // в списке книг у автора может не быть id — тогда берём его из карточки книги
        val authors = books.flatMap { it.authors }.filter { usable(it) }.ifEmpty {
            books.firstOrNull { b -> b.authors.any { matches(it) } }
                ?.let { b -> work(b.id).book.authors.filter { usable(it) } }
                .orEmpty()
        }
        return authors.distinctBy { it.id }.map { a ->
            KnownAuthors.add(a.name, a.id!!)
            AuthorHit(a.id, a.name, "найден по книгам")
        }
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
        }.orEmpty().filter { !isHiddenType(it.book.workType) }

        WorkDetails(book, parents, children)
    }

    suspend fun author(id: String): AuthorDetails = withContext(Dispatchers.IO) {
        val num = id.removePrefix("fl-")
        val o = JSONObject(Http.get("$API/autor/$num?biblio_blocks=1"))
        val sections = mutableListOf<AuthorSection>()
        sections += cycleSections(o.opt("cycles_blocks"))
        // книги, уже показанные внутри циклов, ниже не дублируем
        val inCycles = sections.flatMap { s -> s.books.map { it.id } }.toHashSet()
        sections += blocks(o.opt("works_blocks")).map { s ->
            s.copy(books = s.books.filter { it.id !in inCycles })
        }
        val years = listOf(o.str("birthday"), o.str("deathday"))
            .map { it.take(4) }.filter { it.length == 4 && it != "0000" }.joinToString("–")
        AuthorDetails(
            id = "fl-$num",
            name = o.str("name").ifEmpty { o.str("name_orig") },
            nameOrig = o.str("name_orig"),
            imageUrl = image(o.str("image")),
            years = years,
            sections = sections.map { s -> s.copy(books = s.books.filter { !isHiddenType(it.workType) }) }
                .filter { it.books.isNotEmpty() },
            nameGenitive = o.str("name_rp").ifEmpty { o.str("name").ifEmpty { o.str("name_orig") } },
        )
    }

    /**
     * Роман из частей, которые FantLab показывает ещё и отдельными повестями
     * («Яр(к)ость», «Чудо(вище)» → «(Бес)человечность»): такой роман становится разделом
     * как цикл, а его части больше не дублируются в библиографии.
     */
    suspend fun groupNovelParts(d: AuthorDetails): AuthorDetails = withContext(Dispatchers.IO) {
        val plain = d.sections.filter { it.cycleId == null }
        val works = plain.flatMap { it.books }.filter { !it.isCycle }.distinctBy { it.id }
        fun isNovel(b: Book) = b.workType?.contains("роман", ignoreCase = true) == true
        val novels = works.filter { isNovel(it) }
        val others = works.filter { !isNovel(it) && !isMinor(it.workType) }
        if (novels.isEmpty() || others.isEmpty()) return@withContext d
        val otherIds = others.map { it.id }.toSet()
        // часть не может выйти позже всего романа: старые романы проверять незачем
        val minYear = others.mapNotNull { it.year?.toIntOrNull() }.minOrNull() ?: 0
        val candidates = novels
            .filter { (it.year?.toIntOrNull() ?: Int.MAX_VALUE) >= minYear }
            .sortedByDescending { it.year?.toIntOrNull() ?: Int.MAX_VALUE }
            .take(12)
        val found = candidates.chunked(4).flatMap { part ->
            coroutineScope {
                part.map { n -> async { n to runCatching { work(n.id) }.getOrNull() } }.awaitAll()
            }
        }.mapNotNull { (novel, details) ->
            val parts = details?.let { cycleBooks(it.children) }?.map { it.book }.orEmpty()
            if (parts.size >= 2 && parts.any { it.id in otherIds }) AuthorSection(novel.title, parts, novel.id) else null
        }
        if (found.isEmpty()) return@withContext d
        val moved = found.flatMap { s -> s.books.map { it.id } + s.cycleId!! }.toSet()
        d.copy(
            sections = d.sections.filter { it.cycleId != null } + found +
                plain.map { s -> s.copy(books = s.books.filter { it.id !in moved }) }.filter { it.books.isNotEmpty() },
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

    /** Жанры из классификатора: основные по числу голосов + уверенные поджанры. */
    private fun genres(o: JSONObject): List<String> {
        val groups = o.optJSONObject("classificatory")?.optJSONArray("genre_group") ?: return emptyList()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (!g.str("label").startsWith("Жанр")) continue
            val arr = g.optJSONArray("genre") ?: continue
            val top = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .sortedByDescending { it.optInt("votes") }
            val out = LinkedHashSet<String>()
            top.take(3).forEach { t ->
                t.str("label").takeIf { it.isNotEmpty() }?.let { out += it }
                val sub = t.optJSONArray("genre") ?: return@forEach
                (0 until sub.length()).mapNotNull { sub.optJSONObject(it) }
                    .filter { (it.floatOrNull("percent") ?: 0f) >= 0.3f }
                    .sortedByDescending { it.optInt("votes") }
                    .take(2)
                    .forEach { s -> s.str("label").takeIf { l -> l.isNotEmpty() }?.let { out += it } }
            }
            return out.take(6)
        }
        return emptyList()
    }

    /** Похожие произведения по данным FantLab. */
    suspend fun similars(id: String): List<Book> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Http.get("$API/work/${id.removePrefix("fl-")}/similars"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val wid = o.posInt("id") ?: return@mapNotNull null
            val authors = o.optJSONObject("creators")?.optJSONArray("authors")?.let { a ->
                (0 until a.length()).mapNotNull { k ->
                    val x = a.optJSONObject(k) ?: return@mapNotNull null
                    val name = x.str("name")
                    if (name.isEmpty()) null else Person(x.posInt("id")?.let { "fl-$it" }, name)
                }
            }.orEmpty()
            val saga = o.optJSONObject("saga")
            Book(
                id = "fl-$wid",
                title = o.str("name").ifEmpty { o.str("name_orig") }.ifEmpty { return@mapNotNull null },
                authors = authors,
                coverUrl = image(o.str("image")),
                annotation = cleanText(o.str("description")),
                genres = emptyList(),
                year = o.posInt("year")?.toString(),
                language = null,
                seriesId = saga?.posInt("id")?.let { "fl-$it" },
                seriesName = saga?.str("name")?.ifEmpty { null },
                seriesNumber = null,
                workType = o.str("name_type").ifEmpty { null },
                isCycle = o.str("name_type_icon") in CYCLE_TYPES,
                communityRating = o.optJSONObject("stat")?.floatOrNull("rating"),
                published = o.optInt("published", 1) == 1,
            ).also { BookCache.put(it) }
        }.filter { !isHiddenType(it.workType) }
    }
}

/** Google Книги — для книг, которых нет на FantLab (нон-фикшн, классика и т. п.). */
object GoogleBooks {
    private const val PAGE_SIZE = 20

    /** Запасной источник описания и обложки, когда FantLab недоступен. */
    suspend fun lookup(book: Book): Book? = runCatching {
        val q = listOfNotNull(book.title, book.authors.firstOrNull()?.name).joinToString(" ")
        val title = book.title.lowercase()
        search(q, 1).items.firstOrNull {
            it.title.lowercase().contains(title) && (it.annotation.isNotBlank() || it.coverUrl != null)
        }
    }.getOrNull()

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
            ).also { b -> BookCache.put(b); KnownAuthors.addAll(b.authors) }
        }
        val total = o.optInt("totalItems", 0)
        Page(books, books.isNotEmpty() && start + PAGE_SIZE < total)
    }
}
