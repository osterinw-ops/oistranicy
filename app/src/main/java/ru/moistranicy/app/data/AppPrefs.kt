package ru.moistranicy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object AppPrefs {
    private lateinit var sp: SharedPreferences

    var yearGoal by mutableIntStateOf(24)
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        yearGoal = sp.getInt("yearGoal", 24)
    }

    fun saveYearGoal(value: Int) {
        yearGoal = value.coerceIn(1, 1000)
        sp.edit().putInt("yearGoal", yearGoal).apply()
    }
}
