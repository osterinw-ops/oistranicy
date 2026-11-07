package ru.moistranicy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.moistranicy.app.R
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfEntry

/** Серый «не прочитано» и синий «читаю». */
val EyeGrey = Color(0xFF9A96A8)
val EyeBlue = Color(0xFF3F7BE8)

/**
 * Значок статуса книги в составе цикла:
 * прочитана — рыжая звёздочка с оценкой внутри (без оценки — только контур),
 * читаю — синий глазик, бросил — перечёркнутый глазик, иначе — серый глазик.
 */
@Composable
fun StatusMark(status: ReadStatus?, rating: Int?, size: Dp = 26.dp) {
    when (status) {
        ReadStatus.READ -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (rating != null) {
                Icon(painterResource(R.drawable.ic_star), "Прочитано, оценка ${ratingText(rating)}", tint = Coral, modifier = Modifier.size(size))
                Text(
                    ratingText(rating),
                    color = Color.White,
                    fontSize = (size.value * 0.34f).sp,
                    lineHeight = (size.value * 0.34f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = (size.value * 0.06f).dp),
                )
            } else {
                Icon(painterResource(R.drawable.ic_star_border), "Прочитано", tint = Coral, modifier = Modifier.size(size))
            }
        }
        ReadStatus.READING -> Icon(painterResource(R.drawable.ic_eye), "Читаю", tint = EyeBlue, modifier = Modifier.size(size * 0.85f))
        ReadStatus.DROPPED -> Icon(painterResource(R.drawable.ic_eye_off), "Бросил", tint = EyeGrey, modifier = Modifier.size(size * 0.85f))
        else -> Icon(painterResource(R.drawable.ic_eye), "Не прочитано", tint = EyeGrey.copy(alpha = 0.7f), modifier = Modifier.size(size * 0.85f))
    }
}

/** Оценка 1–10 как число внутри звезды: «9», «10». */
fun ratingText(rating: Int): String = rating.toString()

/** Средняя оценка прочитанных книг (без оценки — не учитываются). null — оценок нет. */
fun averageRating(ids: Collection<String>, shelf: Map<String, ShelfEntry>): Float? {
    val r = ids.mapNotNull { id -> shelf[id]?.takeIf { it.status == ReadStatus.READ }?.rating }
    return if (r.isEmpty()) null else r.average().toFloat()
}

/** Пять маленьких звёзд по оценке 1–10 (с половинками). */
@Composable
fun MiniStars(rating: Float?, size: Dp = 12.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val stars = (rating ?: 0f) / 2f
        for (i in 0 until 5) {
            val fill = (stars - i).coerceIn(0f, 1f)
            Box(Modifier.size(size)) {
                Icon(painterResource(R.drawable.ic_star), null, tint = EyeGrey.copy(alpha = 0.35f), modifier = Modifier.size(size))
                if (fill > 0f) {
                    Box(Modifier.height(size).width(size * (if (fill >= 0.75f) 1f else 0.5f)).clipToBounds()) {
                        Icon(
                            painterResource(R.drawable.ic_star), null, tint = Coral,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).size(size),
                        )
                    }
                }
            }
            if (i < 4) Spacer(Modifier.width(2.dp))
        }
    }
}

/**
 * Инфографика цикла: звёзды средней оценки и полоска «прочитано из всего».
 * Брошенный цикл — серой полоской.
 */
@Composable
fun CycleProgress(read: Int, total: Int, rating: Float?, dropped: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        MiniStars(rating)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val track = MaterialTheme.colorScheme.surfaceVariant
            val bar = if (dropped) EyeGrey else Violet
            val frac = if (total <= 0) 0f else (read.toFloat() / total).coerceIn(0f, 1f)
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(track),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(2.dp)).background(bar))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "$read/$total",
                fontSize = 11.sp,
                color = if (dropped) EyeGrey else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
