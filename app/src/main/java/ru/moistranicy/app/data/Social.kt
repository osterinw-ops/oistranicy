package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.moistranicy.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ------------------------------------------------------------------ модели

data class SUser(
    val id: String,
    val name: String,
    val login: String,
    val avatar: String?,
    val isPublic: Boolean,
    val readCount: Int,
    val followers: Int = 0,
    val following: Int = 0,
    val isFollowing: Boolean = false,
    val isMe: Boolean = false,
    val hidden: Boolean = false,
    val shelf: List<SShelfItem> = emptyList(),
)

data class SShelfItem(
    val bookId: String,
    val title: String,
    val authors: String,
    val cover: String?,
    val series: String?,
    val status: ReadStatus?,
    val rating: Int,
    val updated: Long,
) {
    /** Книга для открытия карточки. */
    fun toBook(): Book = BookCache.get(bookId) ?: Book(
        id = bookId,
        title = title,
        authors = authors.split(", ").filter { it.isNotBlank() }.map { Person(null, it) },
        coverUrl = cover,
        annotation = "",
        genres = emptyList(),
        year = null,
        language = null,
        seriesId = null,
        seriesName = series,
        seriesNumber = null,
    )
}

data class SEvent(
    val id: String,
    val type: String,
    val created: Long,
    val user: SUser?,
    val item: SShelfItem,
    val text: String,
    val likes: Int,
    val liked: Boolean,
    val comments: Int,
)

data class SComment(val id: String, val created: Long, val text: String, val user: SUser?)

// ------------------------------------------------------------------ API

class SocialException(message: String) : IOException(message)

object SocialApi {
    val configured: Boolean get() = BuildConfig.SOCIAL_API_URL.isNotBlank()

    suspend fun call(op: String, payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (!configured) throw SocialException("Сервер ещё не подключён")
        val token = Account.token ?: throw SocialException("Войдите через Яндекс ID в профиле")
        payload.put("op", op)
        val c = URL(BuildConfig.SOCIAL_API_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Yandex-Token", token)
        try {
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299) {
                throw SocialException(json?.optString("error")?.ifEmpty { null } ?: "Сервер ответил кодом $code")
            }
            json ?: JSONObject()
        } finally {
            c.disconnect()
        }
    }

    // ---- разбор ответов

    fun user(o: JSONObject?): SUser? {
        if (o == null || o.optString("id").isEmpty()) return null
        return SUser(
            id = o.optString("id"),
            name = o.optString("name").ifEmpty { "Читатель" },
            login = o.optString("login"),
            avatar = o.optString("avatar").ifEmpty { null },
            isPublic = o.optBoolean("isPublic", true),
            readCount = o.optInt("readCount"),
            followers = o.optInt("followers"),
            following = o.optInt("following"),
            isFollowing = o.optBoolean("isFollowing"),
            isMe = o.optBoolean("isMe"),
            hidden = o.optBoolean("hidden"),
            shelf = o.optJSONArray("shelf")?.let { a -> (0 until a.length()).map { item(a.getJSONObject(it)) } }.orEmpty(),
        )
    }

    fun item(o: JSONObject) = SShelfItem(
        bookId = o.optString("bookId"),
        title = o.optString("title"),
        authors = o.optString("authors"),
        cover = o.optString("cover").ifEmpty { null },
        series = o.optString("series").ifEmpty { null },
        status = runCatching { ReadStatus.valueOf(o.optString("status")) }.getOrNull(),
        rating = o.optInt("rating"),
        updated = o.optLong("updated"),
    )

    fun users(o: JSONObject): List<SUser> =
        o.optJSONArray("users")?.let { a -> (0 until a.length()).mapNotNull { user(a.optJSONObject(it)) } }.orEmpty()

    fun events(o: JSONObject): List<SEvent> =
        o.optJSONArray("events")?.let { a ->
            (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                SEvent(
                    id = e.optString("id"),
                    type = e.optString("type"),
                    created = e.optLong("created"),
                    user = user(e.optJSONObject("user")),
                    item = item(e),
                    text = e.optString("text"),
                    likes = e.optInt("likes"),
                    liked = e.optBoolean("liked"),
                    comments = e.optInt("comments"),
                )
            }
        }.orEmpty()

    fun comments(o: JSONObject): List<SComment> =
        o.optJSONArray("comments")?.let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                SComment(c.optString("id"), c.optLong("created"), c.optString("text"), user(c.optJSONObject("user")))
            }
        }.orEmpty()

    // ---- операции

    suspend fun me() = user(call("me"))
    suspend fun setPublic(on: Boolean) = user(call("setProfile", JSONObject().put("isPublic", on)))
    suspend fun search(q: String) = users(call("search", JSONObject().put("q", q)))
    suspend fun suggest() = users(call("suggest"))
    suspend fun userPage(id: String) = user(call("user", JSONObject().put("id", id)))
    suspend fun follow(id: String) = call("follow", JSONObject().put("id", id))
    suspend fun unfollow(id: String) = call("unfollow", JSONObject().put("id", id))
    suspend fun following(id: String? = null) = users(call("following", JSONObject().apply { id?.let { put("id", it) } }))
    suspend fun followers(id: String? = null) = users(call("followers", JSONObject().apply { id?.let { put("id", it) } }))
    suspend fun feed(before: Long? = null) = events(call("feed", JSONObject().apply { before?.let { put("before", it) } }))
    suspend fun userEvents(id: String, before: Long? = null) =
        events(call("userEvents", JSONObject().put("id", id).apply { before?.let { put("before", it) } }))
    suspend fun like(eventId: String, on: Boolean) = call("like", JSONObject().put("eventId", eventId).put("on", on))
    suspend fun comments(eventId: String) = comments(call("comments", JSONObject().put("eventId", eventId)))
    suspend fun comment(eventId: String, text: String) =
        comments(call("comment", JSONObject().put("eventId", eventId).put("text", text)))
}

// ------------------------------------------------------------------ отправка полки

/**
 * Отправляет на сервер публичную часть полки (книги, статусы, оценки).
 * Друзья видят изменения в ленте. Первая отправка — без событий в ленту.
 */
object SocialSync {
    private lateinit var sp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("social", Context.MODE_PRIVATE)
    }

    private val ready get() = ::sp.isInitialized && SocialApi.configured && Account.isLoggedIn

    fun onShelfChange() {
        if (!ready) return
        pending?.cancel()
        pending = scope.launch {
            delay(8_000)
            push()
        }
    }

    fun pushInBackground() {
        if (!ready) return
        scope.launch { push() }
    }

    /** Сбросить отметку (после выхода — новый аккаунт начнёт с полной отправки). */
    fun reset() {
        if (::sp.isInitialized) sp.edit().clear().apply()
    }

    suspend fun push(): Boolean = mutex.withLock {
        if (!ready) return@withLock false
        val last = sp.getLong("lastPush", 0L)
        val initial = last == 0L || sp.getString("account", null) != Account.login
        val since = if (initial) 0L else last
        val started = System.currentTimeMillis()
        val changed = ShelfRepository.entries.value.values.filter { it.updatedAt > since }
        val deleted = if (initial) emptyList() else ShelfRepository.deletedSince(since)
        if (changed.isEmpty() && deleted.isEmpty() && !initial) return@withLock true
        val arr = JSONArray()
        changed.forEach { e ->
            arr.put(JSONObject().apply {
                put("bookId", e.book.id)
                put("title", e.book.title)
                put("authors", e.book.authorsLine)
                put("cover", e.book.coverUrl ?: "")
                put("series", e.book.seriesName ?: "")
                put("status", e.status.name)
                put("rating", e.rating ?: 0)
                put("updated", e.updatedAt)
            })
        }
        try {
            SocialApi.call(
                "pushShelf",
                JSONObject().put("entries", arr).put("deleted", JSONArray(deleted)).put("initial", initial),
            )
            sp.edit().putLong("lastPush", started).putString("account", Account.login).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
