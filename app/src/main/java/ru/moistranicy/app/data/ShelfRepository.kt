package ru.moistranicy.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Локальная полка: хранится в JSON-файле на телефоне. */
object ShelfRepository {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var deletedFile: File
    private val _entries = MutableStateFlow<Map<String, ShelfEntry>>(emptyMap())
    val entries: StateFlow<Map<String, ShelfEntry>> = _entries
    /** Когда книга была убрана с полки — нужно для синхронизации между устройствами. */
    private val deleted = HashMap<String, Long>()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "shelf.json")
        deletedFile = File(context.filesDir, "shelf_deleted.json")
        repairFile = File(context.filesDir, "shelf_series_checked.txt")
        _entries.value = runCatching { load() }.getOrDefault(emptyMap())
        runCatching {
            if (deletedFile.exists()) {
                val o = JSONObject(deletedFile.readText())
                o.keys().forEach { deleted[it] = o.getLong(it) }
            }
        }
    }

    fun get(id: String): ShelfEntry? = _entries.value[id]

    /** knownDate = false — книга прочитана когда-то раньше: дату не ставим (не идёт в годовой вызов). */
    fun setStatus(book: Book, status: ReadStatus, knownDate: Boolean = true) {
        val now = System.currentTimeMillis()
        val old = _entries.value[book.id]
        val entry = ShelfEntry(
            book = book,
            status = status,
            rating = old?.rating,
            addedAt = old?.addedAt ?: now,
            updatedAt = now,
            startedAt = old?.startedAt ?: if (knownDate && (status == ReadStatus.READING || status == ReadStatus.READ)) now else null,
            finishedAt = if (knownDate && status == ReadStatus.READ) (old?.finishedAt ?: now) else old?.finishedAt,
        )
        synchronized(deleted) { deleted.remove(book.id) }
        update(_entries.value + (book.id to entry))
    }

    fun setRating(id: String, rating: Int?) {
        val old = _entries.value[id] ?: return
        update(_entries.value + (id to old.copy(rating = rating, updatedAt = System.currentTimeMillis())))
    }

    fun remove(id: String) {
        if (!_entries.value.containsKey(id)) return
        synchronized(deleted) { deleted[id] = System.currentTimeMillis() }
        saveDeleted()
        update(_entries.value - id)
    }

    private lateinit var repairFile: File

    /**
     * Книги, сохранённые без цикла (например, отмеченные из списка), попадали во «Вне циклов».
     * В фоне узнаём у FantLab их цикл и обложку — понемногу за каждый запуск.
     */
    fun repairSeriesInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            val tried = runCatching { repairFile.readLines().toHashSet() }.getOrDefault(HashSet())
            val need = _entries.value.values
                .filter { it.book.id.startsWith("fl-") && it.book.seriesId == null && !it.book.isCycle && it.book.id !in tried }
                .take(150)
            if (need.isEmpty()) return@launch
            for (e in need) {
                tried += e.book.id
                val d = runCatching { FantLab.work(e.book.id) }.getOrNull() ?: continue
                val cur = _entries.value[e.book.id]?.book ?: continue
                val nb = cur.copy(
                    seriesId = d.book.seriesId,
                    seriesName = d.book.seriesName,
                    coverUrl = cur.coverUrl ?: d.book.coverUrl,
                    workType = cur.workType ?: d.book.workType,
                    genres = cur.genres.ifEmpty { d.book.genres },
                )
                if (nb != cur) refreshBook(nb)
                delay(150)
            }
            runCatching { repairFile.writeText(tried.joinToString("\n")) }
        }
    }

    /** Подменяет сохранённое описание книги более полным (без изменения статуса и дат). */
    fun refreshBook(book: Book) {
        val old = _entries.value[book.id] ?: return
        if (old.book == book) return
        update(_entries.value + (book.id to old.copy(book = book)))
    }

    private fun update(map: Map<String, ShelfEntry>, notify: Boolean = true) {
        _entries.value = map
        val json = toJson(map.values).toString()
        io.execute { runCatching { file.writeText(json) } }
        if (notify) {
            SyncManager.onLocalChange()
            SocialSync.onShelfChange()
        }
    }

    /** Книги, убранные с полки после указанного времени. */
    fun deletedSince(ts: Long): List<String> = synchronized(deleted) { deleted.filterValues { it > ts }.keys.toList() }

    private fun saveDeleted() {
        val json = synchronized(deleted) { JSONObject().apply { deleted.forEach { (k, v) -> put(k, v) } }.toString() }
        io.execute { runCatching { deletedFile.writeText(json) } }
    }

    // ---------- синхронизация ----------

    fun export(): JSONObject = JSONObject().apply {
        put("entries", toJson(_entries.value.values))
        put("deleted", synchronized(deleted) { JSONObject().apply { deleted.forEach { (k, v) -> put(k, v) } } })
    }

    /** Слияние с копией из облака: по каждой книге побеждает самое позднее изменение. */
    fun mergeRemote(o: JSONObject) {
        val remote = HashMap<String, ShelfEntry>()
        o.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) runCatching { entryFromJson(arr.getJSONObject(i)) }.getOrNull()?.let { remote[it.book.id] = it }
        }
        val remoteDeleted = HashMap<String, Long>()
        o.optJSONObject("deleted")?.let { d -> d.keys().forEach { remoteDeleted[it] = d.getLong(it) } }

        val local = _entries.value
        val result = LinkedHashMap<String, ShelfEntry>()
        synchronized(deleted) {
            remoteDeleted.forEach { (k, v) -> if (v > (deleted[k] ?: 0L)) deleted[k] = v }
            val ids = local.keys + remote.keys
            for (id in ids) {
                val l = local[id]
                val r = remote[id]
                val best = when {
                    l == null -> r
                    r == null -> l
                    r.updatedAt > l.updatedAt -> r.copy(book = mergeBook(r.book, l.book))
                    else -> l.copy(book = mergeBook(l.book, r.book))
                } ?: continue
                val del = deleted[id]
                if (del != null && del >= best.updatedAt) continue
                result[id] = best
            }
        }
        saveDeleted()
        update(result, notify = false)
    }

    private fun mergeBook(main: Book, other: Book): Book = main.copy(
        coverUrl = main.coverUrl ?: other.coverUrl,
        annotation = main.annotation.ifEmpty { other.annotation },
        genres = main.genres.ifEmpty { other.genres },
    )

    private fun load(): Map<String, ShelfEntry> {
        if (!file.exists()) return emptyMap()
        val arr = JSONArray(file.readText())
        val result = LinkedHashMap<String, ShelfEntry>()
        for (i in 0 until arr.length()) {
            val e = entryFromJson(arr.getJSONObject(i))
            result[e.book.id] = e
        }
        return result
    }

    // ---------- JSON ----------

    private fun toJson(list: Collection<ShelfEntry>) = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("book", bookToJson(e.book))
                put("status", e.status.name)
                putOpt("rating", e.rating)
                put("addedAt", e.addedAt)
                put("updatedAt", e.updatedAt)
                putOpt("startedAt", e.startedAt)
                putOpt("finishedAt", e.finishedAt)
            })
        }
    }

    private fun entryFromJson(o: JSONObject) = ShelfEntry(
        book = bookFromJson(o.getJSONObject("book")),
        status = runCatching { ReadStatus.valueOf(o.getString("status")) }.getOrDefault(ReadStatus.WANT),
        rating = o.optIntOrNull("rating"),
        addedAt = o.optLong("addedAt"),
        updatedAt = o.optLong("updatedAt", o.optLong("addedAt")),
        startedAt = o.optLongOrNull("startedAt"),
        finishedAt = o.optLongOrNull("finishedAt"),
    )

    private fun bookToJson(b: Book) = JSONObject().apply {
        put("id", b.id)
        put("title", b.title)
        put("authors", JSONArray().apply {
            b.authors.forEach { a -> put(JSONObject().apply { putOpt("id", a.id); put("name", a.name) }) }
        })
        putOpt("coverUrl", b.coverUrl)
        put("annotation", b.annotation)
        put("genres", JSONArray(b.genres))
        putOpt("year", b.year)
        putOpt("language", b.language)
        putOpt("seriesId", b.seriesId)
        putOpt("seriesName", b.seriesName)
        putOpt("seriesNumber", b.seriesNumber)
        putOpt("workType", b.workType)
        put("isCycle", b.isCycle)
        putOpt("communityRating", b.communityRating?.toDouble())
    }

    private fun bookFromJson(o: JSONObject): Book {
        val authors = o.optJSONArray("authors")
        val genres = o.optJSONArray("genres")
        return Book(
            id = o.getString("id"),
            title = o.optString("title"),
            authors = (0 until (authors?.length() ?: 0)).map {
                val a = authors!!.getJSONObject(it)
                Person(a.optStringOrNull("id"), a.optString("name"))
            },
            coverUrl = o.optStringOrNull("coverUrl"),
            annotation = o.optString("annotation"),
            genres = (0 until (genres?.length() ?: 0)).map { genres!!.getString(it) },
            year = o.optStringOrNull("year"),
            language = o.optStringOrNull("language"),
            seriesId = o.optStringOrNull("seriesId"),
            seriesName = o.optStringOrNull("seriesName"),
            seriesNumber = o.optIntOrNull("seriesNumber"),
            workType = o.optStringOrNull("workType"),
            isCycle = o.optBoolean("isCycle", false),
            communityRating = if (o.has("communityRating") && !o.isNull("communityRating"))
                o.getDouble("communityRating").toFloat() else null,
        )
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
}
