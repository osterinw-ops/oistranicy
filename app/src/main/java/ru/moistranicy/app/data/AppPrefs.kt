package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppPrefs {
    const val DEFAULT_BASE_URL = "https://flibusta.is"

    private lateinit var sp: SharedPreferences

    var baseUrl by mutableStateOf(DEFAULT_BASE_URL)
        private set
    var yearGoal by mutableIntStateOf(24)
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        baseUrl = sp.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        yearGoal = sp.getInt("yearGoal", 24)
    }

    fun saveBaseUrl(value: String) {
        var v = value.trim().trimEnd('/')
        if (v.isEmpty()) v = DEFAULT_BASE_URL
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        baseUrl = v
        sp.edit().putString("baseUrl", v).apply()
    }

    fun saveYearGoal(value: Int) {
        yearGoal = value.coerceIn(1, 1000)
        sp.edit().putInt("yearGoal", yearGoal).apply()
    }
}
