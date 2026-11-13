package ru.moistranicy.app.data.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Глава книги: HTML-файл внутри папки книги и её вес (для процента прочитанного). */
data class Chapter(val path: String, val title: String, val size: Long)

/** Книга в библиотеке читалки. Файлы лежат в files/books/<id>/. */
data class LibBook(
    val id: String,
    val title: String,
    val author: String,
    val series: String?,
    val seriesNo: Int?,
    val format: String,
    val chapters: List<Chapter>,
    val cover: String?,
    /** Книга на FantLab, с которой связан файл (для полки). */
    val linkedId: String? = null,
    val chapter: Int = 0,
    /** Положение внутри главы, 0..1. */
    val frac: Float = 0f,
    /** Прочитано от всей книги, 0..1. */
    val progress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val openedAt: Long = 0,
    /** Предлагали ли уже отметить книгу прочитанной. */
    val finishAsked: Boolean = false,
) {
    val total: Long get() = chapters.sumOf { it.size }.coerceAtLeast(1)
}

/** Библиотека читалки: список книг и место чтения (library.json). */
object Library {
    private lateinit var root: File
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val _books = MutableStateFlow<Map<String, LibBook>>(emptyMap())
    val books: StateFlow<Map<String, LibBook>> = _books

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        root = File(context.filesDir, "books").apply { mkdirs() }
        file = File(context.filesDir, "library.json")
        _books.value = runCatching { load() }.getOrDefault(emptyMap())
    }

    fun dirOf(id: String) = File(root, id)
    fun get(id: String): LibBook? = _books.value[id]

    /** Последняя открытая книга (её открывает кнопка-логотип). */
    fun lastOpened(): LibBook? = _books.value.values.filter { it.openedAt > 0 }.maxByOrNull { it.openedAt }

    /** Файл, связанный с книгой полки. */
    fun byLinked(bookId: String): LibBook? = _books.value.values.firstOrNull { it.linkedId == bookId }

    @Synchronized
    fun put(b: LibBook) = save(_books.value + (b.id to b))

    @Synchronized
    fun update(id: String, f: (LibBook) -> LibBook) {
        val b = _books.value[id] ?: return
        save(_books.value + (id to f(b)))
    }

    @Synchronized
    fun remove(id: String) {
        dirOf(id).deleteRecursively()
        save(_books.value - id)
    }

    /** Сохранить место чтения и посчитать процент по весу глав. */
    fun savePosition(id: String, chapter: Int, frac: Float) = update(id) { b ->
        val before = b.chapters.take(chapter).sumOf { it.size }
        val cur = b.chapters.getOrNull(chapter)?.size ?: 0
        b.copy(
            chapter = chapter, frac = frac,
            progress = ((before + cur * frac) / b.total).toFloat().coerceIn(0f, 1f),
            openedAt = System.currentTimeMillis(),
        )
    }

    private fun save(map: Map<String, LibBook>) {
        _books.value = map
        val json = JSONArray().apply { map.values.forEach { put(toJson(it)) } }.toString()
        io.execute { runCatching { file.writeText(json) } }
    }

    private fun load(): Map<String, LibBook> {
        if (!file.exists()) return emptyMap()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .filter { dirOf(it.id).exists() }
            .associateBy { it.id }
    }

    private fun toJson(b: LibBook) = JSONObject().apply {
        put("id", b.id); put("title", b.title); put("author", b.author)
        putOpt("series", b.series); putOpt("seriesNo", b.seriesNo); put("format", b.format)
        put("chapters", JSONArray().apply {
            b.chapters.forEach { c -> put(JSONObject().apply { put("path", c.path); put("title", c.title); put("size", c.size) }) }
        })
        putOpt("cover", b.cover); putOpt("linkedId", b.linkedId)
        put("chapter", b.chapter); put("frac", b.frac.toDouble()); put("progress", b.progress.toDouble())
        put("addedAt", b.addedAt); put("openedAt", b.openedAt); put("finishAsked", b.finishAsked)
    }

    private fun JSONObject.strOrNull(k: String) = if (has(k) && !isNull(k)) getString(k) else null

    private fun fromJson(o: JSONObject): LibBook {
        val ch = o.optJSONArray("chapters") ?: JSONArray()
        return LibBook(
            id = o.getString("id"),
            title = o.optString("title"),
            author = o.optString("author"),
            series = o.strOrNull("series"),
            seriesNo = if (o.has("seriesNo") && !o.isNull("seriesNo")) o.getInt("seriesNo") else null,
            format = o.optString("format"),
            chapters = (0 until ch.length()).map {
                val c = ch.getJSONObject(it)
                Chapter(c.getString("path"), c.optString("title"), c.optLong("size", 1))
            },
            cover = o.strOrNull("cover"),
            linkedId = o.strOrNull("linkedId"),
            chapter = o.optInt("chapter"),
            frac = o.optDouble("frac", 0.0).toFloat(),
            progress = o.optDouble("progress", 0.0).toFloat(),
            addedAt = o.optLong("addedAt"),
            openedAt = o.optLong("openedAt"),
            finishAsked = o.optBoolean("finishAsked", false),
        )
    }
}
