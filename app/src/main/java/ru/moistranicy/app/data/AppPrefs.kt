package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val title: String) { SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная") }

/** Размеры шрифта: подпись и множитель. */
val FONT_SCALES = listOf("Мелкий" to 0.85f, "Обычный" to 1f, "Крупный" to 1.15f, "Очень крупный" to 1.3f)

object AppPrefs {
    private lateinit var sp: SharedPreferences

    var yearGoal by mutableIntStateOf(24)
        private set
    var fontScale by mutableFloatStateOf(1f)
        private set
    var themeMode by mutableIntStateOf(ThemeMode.SYSTEM.ordinal)
        private set

    /** Типы произведений, которые пользователь скрыл (см. OPTIONAL_TYPES). */
    var hiddenTypes by mutableStateOf<Set<String>>(emptySet())
        private set

    val theme: ThemeMode get() = ThemeMode.entries.getOrElse(themeMode) { ThemeMode.SYSTEM }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        yearGoal = sp.getInt("yearGoal", 24)
        fontScale = sp.getFloat("fontScale", 1f)
        themeMode = sp.getInt("themeMode", ThemeMode.SYSTEM.ordinal)
        hiddenTypes = sp.getStringSet("hiddenTypes", emptySet())?.toSet() ?: emptySet()
    }

    fun setTypeHidden(type: String, hidden: Boolean) {
        hiddenTypes = if (hidden) hiddenTypes + type else hiddenTypes - type
        sp.edit().putStringSet("hiddenTypes", hiddenTypes).apply()
        changed()
    }

    fun saveYearGoal(value: Int) {
        yearGoal = value.coerceIn(1, 1000)
        sp.edit().putInt("yearGoal", yearGoal).apply()
        changed()
    }

    fun saveFontScale(value: Float) {
        fontScale = value
        sp.edit().putFloat("fontScale", value).apply()
        changed()
    }

    fun saveTheme(mode: ThemeMode) {
        themeMode = mode.ordinal
        sp.edit().putInt("themeMode", mode.ordinal).apply()
        changed()
    }

    private fun changed() {
        sp.edit().putLong("prefsChangedAt", System.currentTimeMillis()).apply()
        SyncManager.onLocalChange()
    }

    // ---------- синхронизация ----------

    fun export(): JSONObject = JSONObject().apply {
        put("yearGoal", yearGoal)
        put("fontScale", fontScale.toDouble())
        put("themeMode", themeMode)
        put("hiddenTypes", org.json.JSONArray(hiddenTypes.toList()))
        put("changedAt", sp.getLong("prefsChangedAt", 0L))
    }

    fun mergeRemote(o: JSONObject) {
        val remoteAt = o.optLong("changedAt", 0L)
        if (remoteAt <= sp.getLong("prefsChangedAt", 0L)) return
        yearGoal = o.optInt("yearGoal", yearGoal)
        fontScale = o.optDouble("fontScale", fontScale.toDouble()).toFloat()
        themeMode = o.optInt("themeMode", themeMode)
        o.optJSONArray("hiddenTypes")?.let { a -> hiddenTypes = (0 until a.length()).map { a.getString(it) }.toSet() }
        sp.edit()
            .putInt("yearGoal", yearGoal).putFloat("fontScale", fontScale).putInt("themeMode", themeMode)
            .putStringSet("hiddenTypes", hiddenTypes)
            .putLong("prefsChangedAt", remoteAt).apply()
    }
}
