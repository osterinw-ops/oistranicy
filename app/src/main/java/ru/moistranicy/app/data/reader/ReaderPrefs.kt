package ru.moistranicy.app.data.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Настройки читалки. */
object ReaderPrefs {
    private lateinit var sp: SharedPreferences
    var fontSize by mutableIntStateOf(19)
        private set
    var lineHeight by mutableFloatStateOf(1.5f)
        private set
    var margin by mutableIntStateOf(18)
        private set
    /** 0 — светлая, 1 — сепия, 2 — тёмная. */
    var theme by mutableIntStateOf(0)
        private set
    var serif by mutableStateOf(true)
        private set
    var justify by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        fontSize = sp.getInt("fontSize", 19)
        lineHeight = sp.getFloat("lineHeight", 1.5f)
        margin = sp.getInt("margin", 18)
        theme = sp.getInt("theme", 0)
        serif = sp.getBoolean("serif", true)
        justify = sp.getBoolean("justify", true)
    }

    fun set(
        fontSize: Int = this.fontSize, lineHeight: Float = this.lineHeight, margin: Int = this.margin,
        theme: Int = this.theme, serif: Boolean = this.serif, justify: Boolean = this.justify,
    ) {
        this.fontSize = fontSize.coerceIn(12, 40); this.lineHeight = lineHeight; this.margin = margin
        this.theme = theme; this.serif = serif; this.justify = justify
        sp.edit().putInt("fontSize", this.fontSize).putFloat("lineHeight", lineHeight).putInt("margin", margin)
            .putInt("theme", theme).putBoolean("serif", serif).putBoolean("justify", justify).apply()
    }
}
