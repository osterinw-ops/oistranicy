package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Аккаунт Яндекса: токен и данные профиля. Хранится в приватных настройках приложения. */
object Account {
    private lateinit var sp: SharedPreferences

    var token by mutableStateOf<String?>(null)
        private set
    var displayName by mutableStateOf<String?>(null)
        private set
    var login by mutableStateOf<String?>(null)
        private set
    var avatarUrl by mutableStateOf<String?>(null)
        private set

    val isLoggedIn: Boolean get() = token != null

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        token = sp.getString("token", null)
        displayName = sp.getString("name", null)
        login = sp.getString("login", null)
        avatarUrl = sp.getString("avatar", null)
        SyncManager.lastSyncAt = sp.getLong("lastSync", 0L)
    }

    fun setToken(value: String) {
        token = value
        sp.edit().putString("token", value).apply()
    }

    fun setProfile(name: String?, login: String?, avatar: String?) {
        displayName = name
        this.login = login
        avatarUrl = avatar
        sp.edit().putString("name", name).putString("login", login).putString("avatar", avatar).apply()
    }

    fun saveLastSync(at: Long) = sp.edit().putLong("lastSync", at).apply()

    /** Выход: данные на телефоне остаются, просто перестают синхронизироваться. */
    fun logout() {
        token = null
        displayName = null
        login = null
        avatarUrl = null
        sp.edit().clear().apply()
        SyncManager.lastSyncAt = 0L
    }
}

private class AuthExpired : IOException("Вход устарел — войдите заново")

/** API Яндекс ID и Яндекс Диска (папка приложения). */
private object YandexApi {
    private const val DATA_PATH = "app:/moistranicy.json"

    private fun open(url: String, method: String = "GET", auth: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/json")
        if (auth) Account.token?.let { c.setRequestProperty("Authorization", "OAuth $it") }
        return c
    }

    private fun readBody(c: HttpURLConnection): String =
        (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

    fun userInfo(): JSONObject {
        val c = open("https://login.yandex.ru/info?format=json")
        try {
            if (c.responseCode == 401) throw AuthExpired()
            if (c.responseCode !in 200..299) throw IOException("Яндекс ID: код ${c.responseCode}")
            return JSONObject(readBody(c))
        } finally { c.disconnect() }
    }

    /** Скачать данные из папки приложения на Диске (null — файла ещё нет). */
    fun download(): String? {
        val path = URLEncoder.encode(DATA_PATH, "UTF-8")
        val c = open("https://cloud-api.yandex.net/v1/disk/resources/download?path=$path")
        val href = try {
            when (c.responseCode) {
                404 -> return null
                401 -> throw AuthExpired()
                in 200..299 -> JSONObject(readBody(c)).getString("href")
                else -> throw IOException("Яндекс Диск: код ${c.responseCode}")
            }
        } finally { c.disconnect() }
        val d = open(href, auth = false)
        d.instanceFollowRedirects = true
        try {
            if (d.responseCode !in 200..299) throw IOException("Яндекс Диск: код ${d.responseCode}")
            return d.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { d.disconnect() }
    }

    /** Загрузить данные в папку приложения (с перезаписью). */
    fun upload(body: String) {
        val path = URLEncoder.encode(DATA_PATH, "UTF-8")
        val c = open("https://cloud-api.yandex.net/v1/disk/resources/upload?path=$path&overwrite=true")
        val href = try {
            when (c.responseCode) {
                401 -> throw AuthExpired()
                in 200..299 -> JSONObject(readBody(c)).getString("href")
                else -> throw IOException("Яндекс Диск: код ${c.responseCode} ${readBody(c).take(120)}")
            }
        } finally { c.disconnect() }
        val u = open(href, method = "PUT", auth = false)
        u.doOutput = true
        u.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            u.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (u.responseCode !in 200..299) throw IOException("Яндекс Диск: загрузка, код ${u.responseCode}")
        } finally { u.disconnect() }
    }
}

/**
 * Синхронизация с Яндекс Диском пользователя:
 * скачиваем копию → сливаем с данными на телефоне (побеждает более позднее изменение) → загружаем обратно.
 */
object SyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    @Volatile private var applyingRemote = false

    var syncing by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastSyncAt by mutableLongStateOf(0L)

    /** Вызывается при любом изменении данных на телефоне: синхронизация через 5 секунд тишины. */
    fun onLocalChange() {
        if (applyingRemote || !Account.isLoggedIn) return
        pending?.cancel()
        pending = scope.launch {
            delay(5_000)
            syncNow()
        }
    }

    fun syncInBackground() {
        if (!Account.isLoggedIn) return
        scope.launch { syncNow() }
    }

    /** Полная синхронизация. Возвращает true при успехе. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (!Account.isLoggedIn) return@withContext false
        mutex.withLock {
            syncing = true
            lastError = null
            try {
                val remote = YandexApi.download()?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (remote != null) {
                    applyingRemote = true
                    try {
                        remote.optJSONObject("shelf")?.let { ShelfRepository.mergeRemote(it) }
                        remote.optJSONObject("cycles")?.let { CycleRepository.mergeRemote(it) }
                        remote.optJSONObject("recBlock")?.let { RecBlock.mergeRemote(it) }
                        remote.optJSONObject("prefs")?.let { AppPrefs.mergeRemote(it) }
                    } finally {
                        applyingRemote = false
                    }
                }
                val now = System.currentTimeMillis()
                val out = JSONObject().apply {
                    put("app", "МоиСтраницы")
                    put("version", 1)
                    put("savedAt", now)
                    put("shelf", ShelfRepository.export())
                    put("cycles", CycleRepository.export())
                    put("recBlock", RecBlock.export())
                    put("prefs", AppPrefs.export())
                }
                YandexApi.upload(out.toString())
                lastSyncAt = now
                Account.saveLastSync(now)
                true
            } catch (e: AuthExpired) {
                lastError = e.message
                Account.logout()
                false
            } catch (e: Exception) {
                lastError = e.message ?: "ошибка сети"
                false
            } finally {
                syncing = false
            }
        }
    }

    /** После входа: профиль пользователя и первая синхронизация. */
    suspend fun afterLogin(token: String): Boolean = withContext(Dispatchers.IO) {
        Account.setToken(token)
        runCatching { YandexApi.userInfo() }.getOrNull()?.let { u ->
            val avatarId = u.optString("default_avatar_id")
            val isEmpty = u.optBoolean("is_avatar_empty", avatarId.isEmpty())
            Account.setProfile(
                name = u.optString("display_name").ifEmpty { u.optString("real_name") }.ifEmpty { null },
                login = u.optString("login").ifEmpty { null },
                avatar = if (!isEmpty && avatarId.isNotEmpty())
                    "https://avatars.yandex.net/get-yapic/$avatarId/islands-retina-middle" else null,
            )
        }
        syncNow()
    }
}
