package ru.moistranicy.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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

/** Палитры постеров (на основе цветов логотипа), выбираются по названию серии. */
private val POSTER_GRADIENTS = listOf(
    listOf(Color(0xFF7B6CF6), Color(0xFF2A1F7A)),
    listOf(Color(0xFF5B4BDB), Color(0xFF1B1347)),
    listOf(Color(0xFFFF7A59), Color(0xFF7A2A6E)),
    listOf(Color(0xFF9D91FF), Color(0xFF3A2D9C)),
    listOf(Color(0xFF3A2D9C), Color(0xFFB8401F)),
)

/**
 * Постер серии: настоящая картинка (обложка цикла или первой книги) с затемнением
 * и подписью внизу. Если картинки нет — постер рисуется: градиент, крупный символ
 * жанра и название серии.
 */
@Composable
fun SeriesPoster(
    imageUrl: String?,
    genres: List<String>,
    title: String,
    books: Int?,
    modifier: Modifier = Modifier,
    author: String? = null,
) {
    val grad = POSTER_GRADIENTS[(title.hashCode() and 0x7fffffff) % POSTER_GRADIENTS.size]
    Box(
        modifier
            .size(width = 66.dp, height = 100.dp)
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(grad)),
    ) {
        // рисованный постер (виден, если картинки нет или она не загрузилась)
        // крупный символ жанра — «картинка» постера
        Icon(
            painterResource(themeIconFor(genres)),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(62.dp).align(Alignment.TopCenter).offset(y = 4.dp),
        )
        // тонкая рамка-«виньетка», как у афиши
        Box(
            Modifier
                .fillMaxSize()
                .padding(3.dp)
                .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
        )
        androidx.compose.foundation.layout.Column(
            Modifier.align(Alignment.Center).padding(horizontal = 5.dp).offset(y = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title.uppercase(),
                color = Color.White,
                fontSize = 8.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!author.isNullOrBlank()) {
                Text(
                    author.substringAfterLast(' '),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // затемнение и число книг внизу, как на афише
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(26.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        if (books != null && books > 0) {
            Text(
                "$books кн.",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
            )
        }
        // закладка, как на логотипе
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp)
                .size(width = 6.dp, height = 12.dp)
                .clip(RoundedCornerShape(bottomStart = 1.dp, bottomEnd = 1.dp))
                .background(Coral),
        )
    }
}

/**
 * Горизонтальный постер отдельной книги: фон — затемнённая обложка во всю ширину,
 * слева — обложка целиком. Без обложки — градиент, символ жанра и название.
 */
@Composable
fun BookPosterWide(book: ru.moistranicy.app.data.Book, modifier: Modifier = Modifier) {
    val grad = POSTER_GRADIENTS[(book.title.hashCode() and 0x7fffffff) % POSTER_GRADIENTS.size]
    Box(
        modifier
            .size(width = 120.dp, height = 72.dp)
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(grad)),
    ) {
        // рисованный вариант (виден, если обложки нет)
        Icon(
            painterResource(themeIconFor(book.genres)),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(64.dp).align(Alignment.CenterEnd).offset(x = 8.dp),
        )
        Text(
            book.title,
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 30.dp),
        )
        if (book.coverUrl != null) {
            // фон: обложка, обрезанная по ширине и затемнённая
            AsyncImage(
                model = book.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.65f))),
                ),
            )
            // обложка целиком слева
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(width = 42.dp, height = 60.dp)
                    .shadow(2.dp, RoundedCornerShape(3.dp))
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp)
                .size(width = 5.dp, height = 10.dp)
                .background(Coral),
        )
    }
}

/** Корешок на постере цикла. */
data class Spine(val id: String, val status: ru.moistranicy.app.data.ReadStatus?, val published: Boolean, val isNew: Boolean)

/**
 * Горизонтальный постер цикла «Корешки»: книги цикла стоят на полке,
 * прочитанные закрашены фиолетовым, читаемая — коралловая, непрочитанные — пустые.
 * Отличается от книги и формой (горизонтальный), и смыслом (сразу виден прогресс).
 */
@Composable
fun SeriesShelfPoster(title: String, spines: List<Spine>, modifier: Modifier = Modifier) {
    val read = spines.count { it.published && it.status == ru.moistranicy.app.data.ReadStatus.READ }
    val total = spines.count { it.published }
    val shades = listOf(Color(0xFF8A7CFF), Color(0xFF7B6CF6), Color(0xFF6A5BE6), Color(0xFF5B4BDB), Color(0xFF4A3CC4), Color(0xFF3A2D9C))
    androidx.compose.foundation.layout.Column(
        modifier
            .size(width = 136.dp, height = 84.dp)
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF241E3A), Color(0xFF17132A))))
            .padding(start = 6.dp, end = 6.dp, top = 5.dp),
    ) {
        Text(
            title.uppercase(),
            color = Color.White,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val showNumbers = spines.size <= 12
            spines.forEachIndexed { i, sp ->
                val filled = when {
                    !sp.published -> null
                    sp.status == ru.moistranicy.app.data.ReadStatus.READ -> shades[i % shades.size]
                    sp.status == ru.moistranicy.app.data.ReadStatus.READING -> Coral
                    sp.status == ru.moistranicy.app.data.ReadStatus.DROPPED -> Color(0xFF5A556E)
                    else -> null
                }
                // корешки чуть разной высоты — как настоящие книги
                val h = 0.78f + ((i * 37) % 23) / 100f
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(h)
                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                        .then(
                            if (filled != null) Modifier.background(filled)
                            else Modifier.border(
                                0.6.dp,
                                Color.White.copy(alpha = if (sp.published) 0.35f else 0.15f),
                                RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp),
                            ),
                        ),
                ) {
                    if (sp.isNew) {
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                                .size(4.dp).clip(RoundedCornerShape(2.dp)).background(Coral),
                        )
                    }
                    if (showNumbers) {
                        Text(
                            "${i + 1}",
                            color = Color.White.copy(alpha = if (filled != null) 0.9f else 0.45f),
                            fontSize = 6.sp,
                            lineHeight = 6.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
        // полка
        Box(Modifier.fillMaxWidth().height(3.dp).background(Coral))
        Text(
            if (total == 0) "анонс" else "$read из $total",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 7.sp,
            lineHeight = 8.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Горизонтальный постер цикла — коллаж из обложек его книг:
 * размытый фон из первой обложки, поверх — обложки книг по порядку веером внахлёст,
 * внизу название и прогресс. Без обложек — фирменная плашка с символом жанра.
 */
@Composable
fun SeriesCollagePoster(
    title: String,
    covers: List<String>,
    genres: List<String>,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val grad = POSTER_GRADIENTS[(title.hashCode() and 0x7fffffff) % POSTER_GRADIENTS.size]
    val w = 140.dp
    val h = 86.dp
    Box(
        modifier
            .size(width = w, height = h)
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(grad)),
    ) {
        if (covers.isEmpty()) {
            Icon(
                painterResource(themeIconFor(genres)),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(70.dp).align(Alignment.CenterEnd).offset(x = 6.dp),
            )
        } else {
            // фон: первая обложка, размытая и затемнённая
            AsyncImage(
                model = covers.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(8.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            // обложки веером внахлёст по всей ширине
            val n = covers.size
            val coverW = 42.dp
            val coverH = 62.dp
            val step = if (n <= 1) 0.dp else ((w - coverW - 12.dp) / (n - 1)).coerceAtMost(30.dp)
            val totalW = coverW + step * (n - 1)
            val startX = (w - totalW) / 2
            covers.forEachIndexed { i, url ->
                val mid = (n - 1) / 2f
                val angle = if (n == 1) 0f else (i - mid) * 4f
                Box(
                    Modifier
                        .offset(x = startX + step * i, y = 5.dp + (kotlin.math.abs(i - mid) * 2).dp)
                        .rotate(angle)
                        .size(width = coverW, height = coverH)
                        .shadow(4.dp, RoundedCornerShape(3.dp))
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2A2638)),
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // подпись: название цикла и прогресс
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                color = Color.White,
                fontSize = 8.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (caption != null) {
                Text(caption, color = Coral, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
