package ru.moistranicy.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Словесная оценка для 1–10. */
fun ratingLabel(n: Int): String = when (n) {
    1 -> "Ужасно"
    2 -> "Очень плохо"
    3 -> "Плохо"
    4 -> "Слабо"
    5 -> "Средне"
    6 -> "Неплохо"
    7 -> "Хорошо"
    8 -> "Отлично"
    9 -> "Великолепно"
    10 -> "Шедевр"
    else -> "Без оценки"
}

/**
 * Пять звёзд, каждая половинка — один балл (оценка 1–10).
 * Нажатие на левую половину звезды — нечётная оценка, на правую — чётная; можно вести пальцем.
 */
@Composable
fun StarRating(
    value: Int,
    onChange: (Int) -> Unit,
    starSize: Dp = 40.dp,
    spacing: Dp = 4.dp,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    fun valueAt(x: Float): Int = ((x / width.coerceAtLeast(1)) * 10f + 0.999f).toInt().coerceIn(1, 10)
    Row(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) { detectTapGestures { onChange(valueAt(it.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onChange(valueAt(change.position.x)) }
            },
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        (1..5).forEach { i ->
            val fill = when {
                value >= i * 2 -> 2
                value == i * 2 - 1 -> 1
                else -> 0
            }
            Box(Modifier.size(starSize)) {
                Icon(Icons.Outlined.Star, null, tint = Coral.copy(alpha = 0.55f), modifier = Modifier.size(starSize))
                if (fill == 2) {
                    Icon(Icons.Filled.Star, null, tint = Coral, modifier = Modifier.size(starSize))
                } else if (fill == 1) {
                    // половинка: левая часть закрашенной звезды
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).clipToBounds()) {
                        Icon(
                            Icons.Filled.Star, null, tint = Coral,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).size(starSize),
                        )
                    }
                }
            }
        }
    }
}
