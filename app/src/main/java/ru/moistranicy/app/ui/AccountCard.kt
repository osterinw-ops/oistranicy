package ru.moistranicy.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import kotlinx.coroutines.launch
import ru.moistranicy.app.BuildConfig
import ru.moistranicy.app.data.Account
import ru.moistranicy.app.data.SyncManager

private val YandexRed = Color(0xFFFC3F1D)

/** Аккаунт: вход через Яндекс ID, синхронизация полки с Яндекс Диском. */
@Composable
fun AccountCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        if (Account.isLoggedIn) LoggedIn() else LoggedOut()
    }
}

@Composable
private fun LoggedOut() {
    Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Войдите через Яндекс, чтобы полка, циклы и настройки сохранялись в вашем Яндекс Диске " +
            "и переносились на другие телефоны. Приложение видит только свою папку на Диске.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    if (BuildConfig.YANDEX_CLIENT_ID.isBlank()) {
        Text(
            "Вход через Яндекс скоро появится.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        YandexLoginButton()
    }
    SyncManager.lastError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun YandexLoginButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val sdk = remember { YandexAuthSdk.create(YandexAuthOptions(context)) }
    val launcher = rememberLauncherForActivityResult(sdk.contract) { result ->
        when (result) {
            is YandexAuthResult.Success -> scope.launch { SyncManager.afterLogin(result.token.value) }
            is YandexAuthResult.Failure -> error = result.exception.message ?: "Не удалось войти"
            YandexAuthResult.Cancelled -> Unit
        }
    }
    Button(
        onClick = { error = null; launcher.launch(YandexAuthLoginOptions()) },
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(YandexRed),
            contentAlignment = Alignment.Center,
        ) {
            Text("Я", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text("Войти с Яндекс ID")
    }
    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LoggedIn() {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))) {
            Account.avatarUrl?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Account.displayName ?: Account.login ?: "Яндекс ID", style = MaterialTheme.typography.titleMedium)
            Account.login?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (SyncManager.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(8.dp))
    val last = SyncManager.lastSyncAt
    Text(
        if (last > 0) "Синхронизировано " + DateUtils.getRelativeTimeSpanString(last).toString().lowercase()
        else "Ещё не синхронизировано",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SyncManager.lastError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row {
        TextButton(onClick = { scope.launch { SyncManager.syncNow() } }, enabled = !SyncManager.syncing) {
            Text("Синхронизировать")
        }
        TextButton(onClick = { Account.logout() }) {
            Text("Выйти", color = MaterialTheme.colorScheme.error)
        }
    }
    Text(
        "Данные хранятся в вашем Яндекс Диске: Приложения → МоиСтраницы.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Соцпрофиль: открытость, подписчики, переход на свою страницу и к людям. */
@Composable
fun SocialCard(onUser: (String) -> Unit, onPeople: () -> Unit) {
    if (!ru.moistranicy.app.data.SocialApi.configured || !Account.isLoggedIn) return
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<ru.moistranicy.app.data.SUser?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Account.token) {
        me = runCatching { ru.moistranicy.app.data.SocialApi.me() }.onFailure { error = it.message }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text("Друзья", style = MaterialTheme.typography.titleMedium)
        val m = me
        if (m == null) {
            Text(error ?: "Загрузка…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "Подписчики: ${m.followers} · подписки: ${m.following}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Открытый профиль", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (m.isPublic) "Вас можно найти в поиске, полку видят все"
                        else "Полку видят только подписчики, в поиске вас нет",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = m.isPublic,
                    onCheckedChange = { on ->
                        me = m.copy(isPublic = on)
                        scope.launch { runCatching { ru.moistranicy.app.data.SocialApi.setPublic(on) } }
                    },
                )
            }
            Row {
                TextButton(onClick = { onUser(m.id) }) { Text("Моя страница") }
                TextButton(onClick = onPeople) { Text("Найти людей") }
            }
        }
    }
}
