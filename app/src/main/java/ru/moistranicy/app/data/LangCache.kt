package ru.moistranicy.app.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
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

    /** Растёт при каждой новой проверке: экраны, где видны отметки «не на русском», перерисовываются. */
    private val version = mutableIntStateOf(0)

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

    fun foreign(id: String): Boolean? {
        version.intValue
        return synchronized(this) { map[id] }
    }
    @Synchronized fun has(id: String): Boolean = map.containsKey(id)

    fun put(id: String, foreign: Boolean) {
        synchronized(this) {
            map[id] = foreign
            if (::file.isInitialized) {
                val json = JSONObject(map as Map<*, *>).toString()
                io.execute { runCatching { file.writeText(json) } }
            }
        }
        version.intValue++
    }
}

/** Книга не на русском: по изданиям (если уже проверено), иначе — по названию. */
fun isForeignBook(id: String, title: String): Boolean = LangCache.foreign(id) ?: isForeignTitle(title)
