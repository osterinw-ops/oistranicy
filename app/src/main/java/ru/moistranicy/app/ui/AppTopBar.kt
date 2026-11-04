@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.moistranicy.app.R

/** Логотип приложения: книга с закладкой на фиолетовом градиенте. */
@Composable
fun LogoMark(size: Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(Violet, VioletDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size * 1.6f),
        )
    }
}

/**
 * Шапка вкладки в фирменном стиле: логотип и «Моя полка» —
 * первое слово обычным цветом, остальное фиолетовым, как «МоиСтраницы» на логотипе.
 */
@Composable
fun AppTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val first = title.substringBefore(' ')
    val rest = title.substringAfter(' ', "")
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark()
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(first) }
                        if (rest.isNotEmpty()) {
                            append(" ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)) {
                                append(rest)
                            }
                        }
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        actions = actions,
    )
}
