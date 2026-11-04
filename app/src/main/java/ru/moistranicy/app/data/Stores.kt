package ru.moistranicy.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private val storeIo = Executors.newSingleThreadExecutor()

/**
 * Сколько книг написал автор (вышедшие романы и повести, без рассказов и статей).
 * Нужно для цвета в облаке авторов. Хранится в author_stats.json.
 */
object AuthorStats {
    private lateinit var file: File
    private val _totals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val totals: StateFlow<Map<String, Int>> = _totals
    private val tried = HashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "author_stats.json")
        _totals.value = runCatching {
            if (!file.exists()) emptyMap() else {
                val o = JSONObject(file.readText())
                o.keys().asSequence().associateWith { o.getInt(it) }
            }
        }.getOrDefault(emptyMap())
    }

    /** Догрузить число книг для авторов, которых ещё не знаем (по одному запросу на автора). */
    suspend fun ensure(ids: List<String>) {
        for (id in ids) {
            if (!id.startsWith("fl-") || _totals.value.containsKey(id)) continue
            val firstTry = synchronized(tried) { tried.add(id) }
            if (!firstTry) continue
            val d = runCatching { FantLab.author(id) }.getOrNull() ?: continue
            KnownAuthors.add(d.name, id)
            val count = d.sections.flatMap { it.books }
                .filter { it.published && !it.isCycle && !isMinor(it.workType) }
                .distinctBy { it.id }.size
            put(id, count)
        }
    }

    @Synchronized
    private fun put(id: String, count: Int) {
        _totals.value = _totals.value + (id to count)
        val json = JSONObject().apply { _totals.value.forEach { (k, v) -> put(k, v) } }.toString()
        storeIo.execute { runCatching { file.writeText(json) } }
    }
}

/**
 * Имена авторов, которые приложение уже встречало (поиск, полка, страницы авторов).
 * Используются для подсказок при опечатках.
 */
object KnownAuthors {
    private lateinit var file: File
    private val names = LinkedHashMap<String, String?>() // имя -> fantlab id
    private var dirty = false

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "known_authors.json")
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    names[o.getString("n")] = if (o.has("id")) o.getString("id") else null
                }
            }
        }
    }

    @Synchronized
    fun add(name: String, id: String?) {
        val n = name.trim()
        if (n.length < 3 || n.length > 80) return
        if (names.containsKey(n) && (id == null || names[n] == id)) return
        names[n] = id ?: names[n]
        while (names.size > 5000) names.remove(names.keys.first())
        dirty = true
    }

    fun addAll(people: List<Person>) = people.forEach { add(it.name, it.id?.takeIf { id -> id.startsWith("fl-") }) }

    @Synchronized
    fun snapshot(): Map<String, String?> = LinkedHashMap(names)

    @Synchronized
    fun flush() {
        if (!dirty || !::file.isInitialized) return
        dirty = false
        val arr = JSONArray()
        names.forEach { (n, id) -> arr.put(JSONObject().apply { put("n", n); if (id != null) put("id", id) }) }
        val json = arr.toString()
        storeIo.execute { runCatching { file.writeText(json) } }
    }
}
