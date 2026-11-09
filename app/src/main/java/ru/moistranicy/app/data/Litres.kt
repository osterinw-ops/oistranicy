package ru.moistranicy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** ЛитРес — запасной источник обложек, когда на FantLab обложки нет. */
object Litres {
    private val cache = HashMap<String, String?>()

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Обложка книги по названию и автору; null — не нашли (или ЛитРес недоступен). */
    suspend fun cover(title: String, author: String?): String? {
        val key = norm(title) + "|" + norm(author.orEmpty())
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val found = withContext(Dispatchers.IO) { runCatching { search(title, author) }.getOrNull() }
        synchronized(cache) { cache[key] = found }
        return found
    }

    private fun search(title: String, author: String?): String? {
        val q = listOfNotNull(title, author?.substringAfterLast(' ')).joinToString(" ")
        val url = "https://api.litres.ru/foundation/api/search?q=" + URLEncoder.encode(q, "UTF-8") +
            "&types=text_book&types=audiobook&limit=10"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "MoiStranicy/0.2 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        val data = JSONObject(body).optJSONObject("payload")?.optJSONArray("data") ?: return null
        val want = norm(title)
        val surname = author?.let { norm(it.substringAfterLast(' ')) }.orEmpty()
        var fallback: String? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val inst = item.optJSONObject("instance") ?: item
            val cover = inst.optString("cover_url").ifEmpty { inst.optString("cover") }
            if (cover.isEmpty()) continue
            val t = norm(inst.optString("title"))
            val persons = inst.optJSONArray("persons")
            val names = (0 until (persons?.length() ?: 0)).joinToString(" ") { k ->
                persons!!.optJSONObject(k)?.optString("full_name").orEmpty()
            }.let { norm(it) }
            val titleOk = t == want || t.startsWith(want) || want.startsWith(t)
            val authorOk = surname.isEmpty() || names.isEmpty() || names.contains(surname)
            if (titleOk && authorOk) return absolute(cover)
            if (titleOk && fallback == null && names.isEmpty()) fallback = absolute(cover)
        }
        return fallback
    }

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> "https://www.litres.ru" + (if (path.startsWith("/")) path else "/$path")
    }
}
