@file:OptIn(ExperimentalMaterial3Api::class)

package ru.moistranicy.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.moistranicy.app.R
import ru.moistranicy.app.data.Account
import ru.moistranicy.app.data.BookCache
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.SComment
import ru.moistranicy.app.data.SEvent
import ru.moistranicy.app.data.SShelfItem
import ru.moistranicy.app.data.SUser
import ru.moistranicy.app.data.ShelfRepository
import ru.moistranicy.app.data.SocialApi

// ------------------------------------------------------------------ общие элементы

@Composable
fun UserAvatar(user: SUser?, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, VioletDeep))),
        contentAlignment = Alignment.Center,
    ) {
        val initials = user?.name.orEmpty().split(' ').filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1).uppercase() }
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
        user?.avatar?.let {
            AsyncImage(model = it, contentDescription = user.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun timeAgo(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Текст действия в ленте: «прочитал», «оценил на 9». */
private fun actionText(e: SEvent): String = when (e.type) {
    "rating" -> "оценил(а) на ${e.item.rating}"
    else -> when (e.item.status) {
        ReadStatus.READ -> "прочитал(а)"
        ReadStatus.READING -> "начал(а) читать"
        ReadStatus.WANT -> "хочет прочитать"
        ReadStatus.DROPPED -> "бросил(а)"
        null -> "отметил(а)"
    }
}

/** Открыть книгу из чужой полки: кладём краткие данные в кэш, чтобы карточка открылась. */
fun openSocialBook(item: SShelfItem, onBook: (String) -> Unit) {
    if (BookCache.get(item.bookId) == null) BookCache.put(item.toBook())
    onBook(item.bookId)
}

@Composable
private fun NeedLogin(onProfile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (!SocialApi.configured) "Лента друзей скоро заработает."
            else "Войдите через Яндекс ID, чтобы видеть, что читают друзья, и делиться своими книгами.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (SocialApi.configured) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onProfile) { Text("Войти в профиле") }
        }
    }
}

// ------------------------------------------------------------------ лента

private object FeedState {
    var events by mutableStateOf<List<SEvent>?>(null)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun FeedScreen(
    onBook: (String) -> Unit,
    onUser: (String) -> Unit,
    onPeople: () -> Unit,
    onProfile: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var commentsFor by remember { mutableStateOf<SEvent?>(null) }

    fun load() {
        if (FeedState.loading) return
        FeedState.loading = true
        FeedState.error = null
        scope.launch {
            try {
                val list = SocialApi.feed()
                FeedState.events = list
                FeedState.hasMore = list.size >= 30
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FeedState.error = e.message
            } finally {
                FeedState.loading = false
            }
        }
    }

    fun more() {
        val last = FeedState.events?.lastOrNull() ?: return
        if (FeedState.loadingMore || !FeedState.hasMore) return
        FeedState.loadingMore = true
        scope.launch {
            try {
                val list = SocialApi.feed(before = last.created)
                FeedState.events = FeedState.events.orEmpty() + list
                FeedState.hasMore = list.size >= 30
            } catch (_: Exception) {
                FeedState.hasMore = false
            } finally {
                FeedState.loadingMore = false
            }
        }
    }

    val ready = SocialApi.configured && Account.isLoggedIn
    LaunchedEffect(ready) { if (ready && FeedState.events == null) load() }
    val resetNonce = TabReset.nonces["feed"] ?: 0
    LaunchedEffect(resetNonce) { if (resetNonce > 0) { listState.scrollToItem(0); load() } }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar("Моя лента", actions = {
                if (ready) {
                    IconButton(onClick = onPeople) {
                        Icon(painterResource(R.drawable.ic_group), "Люди", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    if (FeedState.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                    } else {
                        IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                    }
                }
            })
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val events = FeedState.events
            val err = FeedState.error
            when {
                !ready -> NeedLogin(onProfile)
                events == null && FeedState.loading -> Loading()
                err != null && events.isNullOrEmpty() -> CenterMessage("Не удалось загрузить ленту: $err", "Повторить") { load() }
                events.isNullOrEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Здесь будет видно, что читают люди, на которых вы подписаны.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPeople) { Text("Найти людей") }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(events, key = { it.id }) { e ->
                        EventCard(
                            e,
                            onUser = { e.user?.id?.let(onUser) },
                            onBook = { openSocialBook(e.item, onBook) },
                            onLike = {
                                val on = !e.liked
                                FeedState.events = FeedState.events?.map {
                                    if (it.id == e.id) it.copy(liked = on, likes = it.likes + if (on) 1 else -1) else it
                                }
                                scope.launch { runCatching { SocialApi.like(e.id, on) } }
                            },
                            onComments = { commentsFor = e },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                    if (FeedState.hasMore) {
                        item { LoadMoreFooter(FeedState.loadingMore) { more() } }
                    }
                }
            }
        }
    }

    commentsFor?.let { ev ->
        CommentsSheet(ev, onDismiss = { commentsFor = null }, onUser = { commentsFor = null; onUser(it) }) { count ->
            FeedState.events = FeedState.events?.map { if (it.id == ev.id) it.copy(comments = count) else it }
        }
    }
}

@Composable
fun EventCard(e: SEvent, onUser: () -> Unit, onBook: () -> Unit, onLike: () -> Unit, onComments: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onUser)) {
            UserAvatar(e.user, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString { append(e.user?.name ?: "Читатель"); append(" "); append(actionText(e)) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(timeAgo(e.created), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable(onClick = onBook).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(e.item.toBook(), Modifier.width(46.dp), corner = 4.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (e.item.authors.isNotEmpty()) {
                    Text(e.item.authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                e.item.series?.let {
                    Text("Цикл «$it»", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                if (e.item.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = Coral, modifier = Modifier.size(14.dp))
                        Text(" ${e.item.rating}/10", style = MaterialTheme.typography.labelMedium, color = Coral)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLike) {
                Icon(
                    if (e.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Нравится",
                    tint = if (e.liked) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (e.likes > 0) Text("${e.likes}", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onComments) {
                Icon(
                    painterResource(R.drawable.ic_chat_bubble),
                    "Комментарии",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            if (e.comments > 0) Text("${e.comments}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CommentsSheet(e: SEvent, onDismiss: () -> Unit, onUser: (String) -> Unit, onCount: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SComment>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(e.id) {
        list = runCatching { SocialApi.comments(e.id) }.onFailure { error = it.message }.getOrNull() ?: emptyList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Комментарии", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${e.user?.name ?: ""} ${actionText(e)} «${e.item.title}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val l = list
                when {
                    l == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    l.isEmpty() -> Text("Пока нет комментариев", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    else -> l.forEach { c ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Box(Modifier.clickable { c.user?.id?.let(onUser) }) { UserAvatar(c.user, 30.dp) }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.user?.name ?: "Читатель", style = MaterialTheme.typography.labelLarge)
                                Text(c.text, style = MaterialTheme.typography.bodyMedium)
                                Text(timeAgo(c.created), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1000) },
                    placeholder = { Text("Написать комментарий") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val t = text.trim()
                        if (t.isEmpty() || sending) return@IconButton
                        sending = true
                        scope.launch {
                            try {
                                val updated = SocialApi.comment(e.id, t)
                                list = updated
                                text = ""
                                onCount(updated.size)
                            } catch (ex: Exception) {
                                error = ex.message
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

// ------------------------------------------------------------------ люди

@Composable
fun PeopleScreen(onBack: () -> Unit, onUser: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<SUser>?>(null) }
    var lists by remember { mutableStateOf<Map<Int, List<SUser>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("Рекомендуем", "Подписки", "Подписчики")

    LaunchedEffect(tab) {
        if (lists.containsKey(tab)) return@LaunchedEffect
        loading = true
        error = null
        try {
            val l = when (tab) {
                0 -> SocialApi.suggest()
                1 -> SocialApi.following()
                else -> SocialApi.followers()
            }
            lists = lists + (tab to l)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    fun search() {
        val q = query.trim()
        if (q.length < 2) return
        loading = true
        scope.launch {
            try { results = SocialApi.search(q) } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Люди") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) results = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Имя или логин Яндекса") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            val res = results
            if (res == null) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
            }
            val shown = res ?: lists[tab]
            val err = error
            when {
                loading && shown == null -> Loading()
                err != null && shown == null -> CenterMessage(err)
                shown.isNullOrEmpty() -> CenterMessage(
                    when {
                        res != null -> "Никого не нашли"
                        tab == 1 -> "Вы пока ни на кого не подписаны"
                        tab == 2 -> "Пока нет подписчиков"
                        else -> "Пока никого нет — пригласите друзей в приложение"
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { u -> UserRow(u) { onUser(u.id) } }
                }
            }
        }
    }
}

@Composable
private fun UserRow(u: SUser, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(u, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, style = MaterialTheme.typography.titleSmall)
            Text("прочитано книг: ${u.readCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ страница пользователя

@Composable
fun UserScreen(id: String, onBack: () -> Unit, onBook: (String) -> Unit, onUser: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember(id) { mutableStateOf<SUser?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var tab by remember(id) { mutableIntStateOf(0) }
    var busy by remember(id) { mutableStateOf(false) }
    val myShelf by ShelfRepository.entries.collectAsState()

    LaunchedEffect(id) {
        try { user = SocialApi.userPage(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text(user?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val u = user
        val err = error
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                u == null && err != null -> CenterMessage(err)
                u == null -> Loading()
                else -> {
                    val tabs = listOf(ReadStatus.READING, ReadStatus.READ, ReadStatus.WANT)
                    val list = u.shelf.filter { it.status == tabs[tab] }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                UserAvatar(u, 84.dp)
                                Spacer(Modifier.height(8.dp))
                                Text(u.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Stat("${u.readCount}", "прочитано")
                                    Stat("${u.followers}", "подписчики")
                                    Stat("${u.following}", "подписки")
                                }
                                if (!u.isMe) {
                                    Spacer(Modifier.height(12.dp))
                                    if (u.isFollowing) {
                                        OutlinedButton(enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                runCatching { SocialApi.unfollow(u.id) }
                                                user = u.copy(isFollowing = false, followers = (u.followers - 1).coerceAtLeast(0))
                                                busy = false
                                            }
                                        }) { Text("Вы подписаны") }
                                    } else {
                                        Button(enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                runCatching { SocialApi.follow(u.id) }
                                                user = u.copy(isFollowing = true, followers = u.followers + 1)
                                                busy = false
                                            }
                                        }) {
                                            Icon(painterResource(R.drawable.ic_person_add), null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Подписаться")
                                        }
                                    }
                                    TasteMatch(u, myShelf.values.filter { it.status == ReadStatus.READ }.map { it.book.id to it.book.authorsLine })
                                }
                            }
                        }
                        if (u.hidden) {
                            item { CenterMessage("Профиль закрыт. Подпишитесь, чтобы видеть полку.") }
                        } else {
                            item {
                                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                                    tabs.forEachIndexed { i, s ->
                                        Tab(selected = tab == i, onClick = { tab = i }, text = {
                                            Text("${s.title} ${u.shelf.count { it.status == s }}")
                                        })
                                    }
                                }
                            }
                            if (list.isEmpty()) {
                                item { Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                            }
                            items(list, key = { it.bookId }) { it2 ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { openSocialBook(it2, onBook) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BookCover(it2.toBook(), Modifier.width(48.dp), corner = 4.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(it2.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(it2.authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        it2.series?.let { s -> Text("Цикл «$s»", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                    }
                                    if (it2.rating > 0) Text("★ ${it2.rating}", color = Coral, style = MaterialTheme.typography.labelLarge)
                                    if (myShelf[it2.bookId] != null) {
                                        Spacer(Modifier.width(6.dp))
                                        StatusBadge(myShelf.getValue(it2.bookId).status)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Совпадение вкусов»: общие прочитанные книги и авторы. */
@Composable
private fun TasteMatch(u: SUser, mineRead: List<Pair<String, String>>) {
    val theirRead = u.shelf.filter { it.status == ReadStatus.READ }
    if (theirRead.isEmpty() || mineRead.isEmpty()) return
    val myBooks = mineRead.map { it.first }.toSet()
    val myAuthors = mineRead.flatMap { it.second.split(", ") }.filter { it.isNotBlank() }.toSet()
    val theirAuthors = theirRead.flatMap { it.authors.split(", ") }.filter { it.isNotBlank() }.toSet()
    val commonBooks = theirRead.count { it.bookId in myBooks }
    val commonAuthors = theirAuthors.intersect(myAuthors)
    val authorScore = if (theirAuthors.isEmpty() || myAuthors.isEmpty()) 0.0
    else commonAuthors.size.toDouble() / minOf(theirAuthors.size, myAuthors.size)
    val bookScore = commonBooks.toDouble() / minOf(theirRead.size, myBooks.size)
    val percent = ((authorScore * 0.6 + bookScore * 0.4) * 100).toInt().coerceIn(0, 100)
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.25f), Coral.copy(alpha = 0.2f))))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Совпадение вкусов $percent%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Общих книг: $commonBooks · общих авторов: ${commonAuthors.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (commonAuthors.isNotEmpty()) {
            Text(
                commonAuthors.take(4).joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
