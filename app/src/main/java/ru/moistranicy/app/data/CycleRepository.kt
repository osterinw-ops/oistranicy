package ru.moistranicy.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Новая (или только что вышедшая) книга в отслеживаемом цикле. */
data class CycleNews(val cycleId: String, val cycleName: String, val book: CycleBook)

/** Отслеживаемые циклы. Хранятся в cycles.json на телефоне. */
object CycleRepository {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val _cycles = MutableStateFlow<Map<String, TrackedCycle>>(emptyMap())
    val cycles: StateFlow<Map<String, TrackedCycle>> = _cycles

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "cycles.json")
        _cycles.value = runCatching { load() }.getOrDefault(emptyMap())
    }

    fun get(id: String): TrackedCycle? = _cycles.value[id]
    fun isTracked(id: String) = _cycles.value.containsKey(id)

    /** Начать следить за циклом: details — карточка самого цикла (с составом). */
    fun follow(details: WorkDetails) {
        val now = System.currentTimeMillis()
        val old = _cycles.value[details.book.id]
        val cycle = TrackedCycle(
            id = details.book.id,
            name = details.book.title,
            authors = details.book.authors,
            coverUrl = details.book.coverUrl,
            books = toCycleBooks(details).map { b ->
                b.copy(coverUrl = b.coverUrl ?: old?.books?.firstOrNull { it.id == b.id }?.coverUrl)
            },
            newIds = old?.newIds ?: emptySet(),
            followedAt = old?.followedAt ?: now,
            checkedAt = now,
            genres = details.book.genres.ifEmpty {
                old?.genres?.ifEmpty { null } ?: firstBooksGenres(details)
            },
            dropped = old?.dropped ?: false,
        )
        save(_cycles.value + (cycle.id to cycle))
    }

    /** Если цикл ещё не отслеживается — загрузить и начать следить. */
    suspend fun ensureFollowed(cycleId: String): TrackedCycle? {
        get(cycleId)?.let { return it }
        val d = FantLab.work(cycleId)
        follow(d)
        return get(cycleId)
    }

    fun unfollow(id: String) = save(_cycles.value - id)

    /** Бросить цикл: все непрочитанные вышедшие книги — в «Бросил», цикл больше не предлагаем. */
    fun dropCycle(id: String) {
        val c = _cycles.value[id] ?: return
        val shelf = ShelfRepository.entries.value
        c.books.filter { it.published }.forEach { b ->
            val st = shelf[b.id]?.status
            if (st != ReadStatus.READ && st != ReadStatus.DROPPED) {
                ShelfRepository.setStatus(bookOf(c, b), ReadStatus.DROPPED)
            }
        }
        save(_cycles.value + (id to c.copy(dropped = true, newIds = emptySet())))
    }

    /** Вернуться к брошенному циклу: книги со статусом «Бросил» снимаются с полки. */
    fun undropCycle(id: String) {
        val c = _cycles.value[id] ?: return
        val shelf = ShelfRepository.entries.value
        c.books.forEach { b -> if (shelf[b.id]?.status == ReadStatus.DROPPED) ShelfRepository.remove(b.id) }
        save(_cycles.value + (id to c.copy(dropped = false)))
    }

    private fun firstBooksGenres(d: WorkDetails): List<String> =
        cycleBooks(d.children).take(3).flatMap { BookCache.get(it.book.id)?.genres.orEmpty() }.distinct().take(6)

    /** Запомнить жанры цикла (когда их удалось узнать по первым книгам). */
    private fun updateGenres(cycleId: String, genres: List<String>) {
        val c = _cycles.value[cycleId] ?: return
        if (genres.isEmpty() || c.genres.isNotEmpty()) return
        save(_cycles.value + (cycleId to c.copy(genres = genres.distinct().take(6))))
    }

    fun markSeen(id: String) {
        val c = _cycles.value[id] ?: return
        if (c.newIds.isEmpty()) return
        save(_cycles.value + (id to c.copy(newIds = emptySet())))
    }

    fun markAllSeen() {
        if (_cycles.value.values.none { it.newIds.isNotEmpty() }) return
        save(_cycles.value.mapValues { it.value.copy(newIds = emptySet()) })
    }

    /**
     * Обновить цикл свежими данными. Возвращает книги, которых раньше не было,
     * и анонсы, которые теперь вышли.
     */
    fun applyUpdate(details: WorkDetails): List<CycleNews> {
        val old = _cycles.value[details.book.id] ?: return emptyList()
        val fresh = toCycleBooks(details)
        if (fresh.isEmpty()) return emptyList()
        val oldById = old.books.associateBy { it.id }
        val freshWithCovers = fresh.map { b -> b.copy(coverUrl = b.coverUrl ?: oldById[b.id]?.coverUrl) }
        val news = fresh.filter { b ->
            val prev = oldById[b.id]
            (prev == null) || (!prev.published && b.published)
        }
        val updated = old.copy(
            name = details.book.title.ifBlank { old.name },
            coverUrl = details.book.coverUrl ?: old.coverUrl,
            books = freshWithCovers,
            newIds = old.newIds + news.map { it.id },
            checkedAt = System.currentTimeMillis(),
            genres = details.book.genres.ifEmpty { old.genres },
        )
        save(_cycles.value + (old.id to updated))
        return news.map { CycleNews(old.id, updated.name, it) }
    }

    /** Проверить все отслеживаемые циклы. Ошибки по отдельным циклам пропускаются. */
    suspend fun checkAll(): List<CycleNews> {
        val result = mutableListOf<CycleNews>()
        for (id in _cycles.value.filterValues { !it.dropped }.keys.toList()) {
            runCatching { FantLab.work(id) }.getOrNull()?.let { result += applyUpdate(it) }
        }
        return result
    }

    /** Обложки для коллажа цикла: до трёх первых вышедших книг. */
    fun collageCovers(c: TrackedCycle): List<String> =
        c.books.filter { it.published }.mapNotNull { it.coverUrl }.take(3)
            .ifEmpty { listOfNotNull(c.coverUrl) }

    /** Запомнить обложку книги, если она входит в отслеживаемые циклы. */
    fun updateCover(bookId: String, url: String?) {
        if (url == null) return
        var changed = false
        val map = _cycles.value.mapValues { (_, c) ->
            if (c.books.none { it.id == bookId && it.coverUrl != url }) c
            else {
                changed = true
                c.copy(books = c.books.map { if (it.id == bookId) it.copy(coverUrl = url) else it })
            }
        }
        if (changed) save(map)
    }

    private val coverJobs = HashSet<String>()
    private val metaTried = HashSet<String>()

    /** Догрузить обложки первых трёх книг цикла с FantLab (один раз). */
    suspend fun ensureCovers(cycleId: String) {
        val c = get(cycleId) ?: return
        val first = c.books.filter { it.published }.take(3)
        val need = first.filter { it.coverUrl == null || c.genres.isEmpty() }
        if (need.isEmpty()) return
        synchronized(coverJobs) {
            if (!metaTried.add(cycleId)) return   // в этой сессии уже пробовали
            if (!coverJobs.add(cycleId)) return
        }
        try {
            val genres = mutableListOf<String>()
            for (b in need) {
                val book = runCatching { FantLab.work(b.id).book }.getOrNull() ?: BookCache.get(b.id)
                updateCover(b.id, book?.coverUrl)
                genres += book?.genres.orEmpty()
            }
            updateGenres(cycleId, genres)
        } finally {
            synchronized(coverJobs) { coverJobs.remove(cycleId) }
        }
    }

    /** Книга как объект Book (для статуса/полки), берём подробную из кэша, если есть. */
    fun bookOf(cycle: TrackedCycle, b: CycleBook): Book =
        BookCache.get(b.id) ?: Book(
            id = b.id, title = b.title, authors = cycle.authors, coverUrl = null, annotation = "",
            genres = emptyList(), year = b.year, language = null, seriesId = cycle.id,
            seriesName = cycle.name, seriesNumber = cycle.books.indexOfFirst { it.id == b.id } + 1,
            workType = b.type, published = b.published,
        )

    private fun toCycleBooks(d: WorkDetails): List<CycleBook> =
        cycleBooks(d.children).map {
            CycleBook(it.book.id, it.book.title, it.book.year, it.book.workType, it.published,
                it.book.coverUrl ?: BookCache.get(it.book.id)?.coverUrl)
        }

    @Synchronized
    private fun save(map: Map<String, TrackedCycle>) {
        _cycles.value = map
        val json = JSONArray().apply { map.values.forEach { put(toJson(it)) } }.toString()
        io.execute { runCatching { file.writeText(json) } }
    }

    private fun load(): Map<String, TrackedCycle> {
        if (!file.exists()) return emptyMap()
        val arr = JSONArray(file.readText())
        val out = LinkedHashMap<String, TrackedCycle>()
        for (i in 0 until arr.length()) {
            val c = fromJson(arr.getJSONObject(i))
            out[c.id] = c
        }
        return out
    }

    private fun toJson(c: TrackedCycle) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("authors", JSONArray().apply {
            c.authors.forEach { a -> put(JSONObject().apply { putOpt("id", a.id); put("name", a.name) }) }
        })
        putOpt("coverUrl", c.coverUrl)
        put("books", JSONArray().apply {
            c.books.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("title", b.title); putOpt("year", b.year)
                    putOpt("type", b.type); put("published", b.published); putOpt("coverUrl", b.coverUrl)
                })
            }
        })
        put("newIds", JSONArray(c.newIds.toList()))
        put("followedAt", c.followedAt)
        put("checkedAt", c.checkedAt)
        put("genres", JSONArray(c.genres))
        put("dropped", c.dropped)
    }

    private fun fromJson(o: JSONObject): TrackedCycle {
        val authors = o.optJSONArray("authors") ?: JSONArray()
        val books = o.optJSONArray("books") ?: JSONArray()
        val newIds = o.optJSONArray("newIds") ?: JSONArray()
        return TrackedCycle(
            id = o.getString("id"),
            name = o.optString("name"),
            authors = (0 until authors.length()).map {
                val a = authors.getJSONObject(it)
                Person(if (a.has("id") && !a.isNull("id")) a.getString("id") else null, a.optString("name"))
            },
            coverUrl = if (o.has("coverUrl") && !o.isNull("coverUrl")) o.getString("coverUrl") else null,
            books = (0 until books.length()).map {
                val b = books.getJSONObject(it)
                CycleBook(
                    id = b.getString("id"),
                    title = b.optString("title"),
                    year = if (b.has("year") && !b.isNull("year")) b.getString("year") else null,
                    type = if (b.has("type") && !b.isNull("type")) b.getString("type") else null,
                    published = b.optBoolean("published", true),
                    coverUrl = if (b.has("coverUrl") && !b.isNull("coverUrl")) b.getString("coverUrl") else null,
                )
            },
            newIds = (0 until newIds.length()).map { newIds.getString(it) }.toSet(),
            followedAt = o.optLong("followedAt"),
            checkedAt = o.optLong("checkedAt"),
            genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
            dropped = o.optBoolean("dropped", false),
        )
    }
}
