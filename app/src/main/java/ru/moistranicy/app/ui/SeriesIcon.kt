package ru.moistranicy.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.moistranicy.app.R

/** Тема цикла по жанрам FantLab: ключевые слова → иконка. Порядок — от частного к общему. */
private val THEMES: List<Pair<List<String>, Int>> = listOf(
    listOf("литрпг", "компьютерн", "игр") to R.drawable.ic_theme_sports_esports,
    listOf("киберпанк", "биопанк", "технотриллер") to R.drawable.ic_theme_memory,
    listOf("постапокал", "апокал", "катастроф") to R.drawable.ic_theme_local_fire_department,
    listOf("космо", "космич", "звёздн", "звездн", "инопланет") to R.drawable.ic_theme_rocket_launch,
    listOf("городск", "урбан") to R.drawable.ic_theme_location_city,
    listOf("ужас", "хоррор") to R.drawable.ic_theme_skull,
    listOf("мистик", "вампир", "оборотн", "готик") to R.drawable.ic_theme_dark_mode,
    listOf("детектив", "триллер", "шпион") to R.drawable.ic_theme_search,
    listOf("любов", "романтик", "мелодрам") to R.drawable.ic_theme_favorite,
    listOf("военн", "боевик", "боев", "милитар") to R.drawable.ic_theme_military_tech,
    listOf("историч", "истори", "альтернативн") to R.drawable.ic_theme_history_edu,
    listOf("морск", "пират") to R.drawable.ic_theme_sailing,
    listOf("приключ", "путешеств") to R.drawable.ic_theme_explore,
    listOf("детск", "для детей") to R.drawable.ic_theme_child_care,
    listOf("животн", "зоо") to R.drawable.ic_theme_pets,
    listOf("психолог", "философ") to R.drawable.ic_theme_psychology,
    listOf("эпическ", "героическ", "меч и магия") to R.drawable.ic_theme_swords,
    listOf("сказк", "волшеб", "магич", "маги") to R.drawable.ic_theme_auto_awesome,
    listOf("средневек", "рыцар") to R.drawable.ic_theme_castle,
    listOf("фэнтези") to R.drawable.ic_theme_swords,
    listOf("научн", "фантаст", "нф") to R.drawable.ic_theme_science,
    listOf("природ", "реализм", "деревенск") to R.drawable.ic_theme_landscape,
)

@DrawableRes
fun themeIconFor(genres: List<String>): Int {
    val g = genres.map { it.lowercase() }
    for ((keys, icon) in THEMES) {
        if (g.any { label -> keys.any { label.contains(it) } }) return icon
    }
    return R.drawable.ic_theme_menu_book
}

/**
 * Иконка цикла в стиле логотипа: фиолетовая плитка, белый символ темы
 * и коралловая закладка в углу.
 */
@Composable
fun SeriesIcon(genres: List<String>, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Violet, VioletDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(themeIconFor(genres)),
            contentDescription = title,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
        // закладка, как на логотипе
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp)
                .offset(y = 0.dp)
                .size(width = 6.dp, height = 11.dp)
                .clip(RoundedCornerShape(bottomStart = 1.dp, bottomEnd = 1.dp))
                .background(Coral),
        )
    }
}
