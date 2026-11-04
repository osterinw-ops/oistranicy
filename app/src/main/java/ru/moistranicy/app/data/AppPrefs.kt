package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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

    val theme: ThemeMode get() = ThemeMode.entries.getOrElse(themeMode) { ThemeMode.SYSTEM }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        yearGoal = sp.getInt("yearGoal", 24)
        fontScale = sp.getFloat("fontScale", 1f)
        themeMode = sp.getInt("themeMode", ThemeMode.SYSTEM.ordinal)
    }

    fun saveYearGoal(value: Int) {
        yearGoal = value.coerceIn(1, 1000)
        sp.edit().putInt("yearGoal", yearGoal).apply()
    }

    fun saveFontScale(value: Float) {
        fontScale = value
        sp.edit().putFloat("fontScale", value).apply()
    }

    fun saveTheme(mode: ThemeMode) {
        themeMode = mode.ordinal
        sp.edit().putInt("themeMode", mode.ordinal).apply()
    }
}
