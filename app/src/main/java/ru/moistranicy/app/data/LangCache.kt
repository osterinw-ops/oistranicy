package ru.moistranicy.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Вышла ли книга на русском — по изданиям на FantLab (запрашивается один раз на книгу).
 * id → true, если книга НЕ издавалась на русском.
 */
object LangCache {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val map = HashMap<String, Boolean>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "lang_cache.json")
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { map[it] = o.getBoolean(it) }
            }
        }
    }

    @Synchronized fun foreign(id: String): Boolean? = map[id]
    @Synchronized fun has(id: String): Boolean = map.containsKey(id)

    @Synchronized
    fun put(id: String, foreign: Boolean) {
        map[id] = foreign
        if (!::file.isInitialized) return
        val json = JSONObject(map as Map<*, *>).toString()
        io.execute { runCatching { file.writeText(json) } }
    }
}

/** Книга не на русском: по изданиям (если уже проверено), иначе — по названию. */
fun isForeignBook(id: String, title: String): Boolean = LangCache.foreign(id) ?: isForeignTitle(title)
