package ru.moistranicy.app.data

enum class ReadStatus(val title: String) {
    READING("Читаю"),
    WANT("Хочу прочитать"),
    READ("Прочитал"),
    /** Больше не используется: брошенные книги при запуске снимаются с полки. */
    DROPPED("Бросил"),
}

/** Статусы, которые видит пользователь (механика «Бросил» убрана). */
val VISIBLE_STATUSES = listOf(ReadStatus.READING, ReadStatus.WANT, ReadStatus.READ)

/** Автор. id — в формате источника, например "fl-1". */
data class Person(val id: String?, val name: String)

/** Ссылка на цикл, в который входит книга. */
data class SeriesRef(val id: String, val name: String)

/**
 * Книга (произведение). id с префиксом источника:
 * "fl-123" — FantLab, "gb-abc" — Google Книги.
 */
data class Book(
    val id: String,
    val title: String,
    val authors: List<Person>,
    val coverUrl: String?,
    val annotation: String,
    val genres: List<String>,
    val year: String?,
    val language: String?,
    val seriesId: String?,
    val seriesName: String?,
    val seriesNumber: Int?,
    val workType: String? = null,
    val isCycle: Boolean = false,
    val communityRating: Float? = null,
    /** false — книга анонсирована, но ещё не вышла. В JSON не сохраняется. */
    val published: Boolean = true,
) {
    val authorsLine: String get() = authors.joinToString(", ") { it.name }
    val source: String get() = id.substringBefore('-', "")
}

data class ShelfEntry(
    val book: Book,
    val status: ReadStatus,
    val rating: Int?,
    val addedAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)

/** Книга внутри цикла: deep — уровень вложенности (1 — прямые части). */
data class CycleItem(val book: Book, val deep: Int, val published: Boolean = true)

/** Книга отслеживаемого цикла (номер = позиция в списке + 1). */
data class CycleBook(
    val id: String,
    val title: String,
    val year: String?,
    val type: String?,
    val published: Boolean,
    val coverUrl: String? = null,
    /** Книга написана не основным автором цикла (межавторские продолжения и т. п.). */
    val side: Boolean = false,
    /** Книга не издана на русском (название только на языке оригинала). */
    val foreign: Boolean = false,
) {
    /** Необязательная книга: не нужна, чтобы цикл считался прочитанным. */
    val optional: Boolean get() = side || foreign
}

/** Цикл, за которым следит пользователь. newIds — книги, появившиеся с прошлого просмотра. */
data class TrackedCycle(
    val id: String,
    val name: String,
    val authors: List<Person>,
    val coverUrl: String?,
    val books: List<CycleBook>,
    val newIds: Set<String>,
    val followedAt: Long,
    val checkedAt: Long,
    /** Жанры FantLab (цикла или его первых книг) — для тематической иконки. */
    val genres: List<String> = emptyList(),
    /** Пользователь бросил цикл целиком: не предлагаем и не уведомляем. */
    val dropped: Boolean = false,
    /** Время последнего изменения — для синхронизации между устройствами. */
    val changedAt: Long = 0,
)

private val MINOR_TYPES = setOf(
    "статья", "эссе", "стихотворение", "стихотворения", "очерк", "отрывок",
    "интервью", "рецензия", "предисловие", "послесловие", "пьеса-шутка",
)

/** Антологии и сборники не показываем никогда: они повторяют входящие в них книги. */
private val ALWAYS_HIDDEN_TYPES = setOf("антология", "сборник")

/** Типы произведений, которые можно скрыть в настройках: тип FantLab → подпись. */
val OPTIONAL_TYPES = listOf(
    "статья" to "Статьи",
    "рецензия" to "Рецензии",
    "интервью" to "Интервью",
    "киносценарий" to "Киносценарии",
    "комикс" to "Комиксы",
    "документальн" to "Документальные",
    "графическ" to "Графические",
    "эссе" to "Эссе",
    "библиограф" to "Библиография",
    "прочее" to "Прочие",
)

/** Начало заголовка раздела библиографии для каждого скрываемого типа. */
private val SECTION_PREFIX = mapOf(
    "статья" to "стать", "рецензия" to "рецензи", "интервью" to "интервью", "киносценарий" to "киносценари",
    "комикс" to "комикс", "документальн" to "документальн", "графическ" to "графическ", "эссе" to "эссе",
    "библиограф" to "библиограф", "прочее" to "прочие",
)

/** Раздел библиографии автора («Статьи», «Эссе», «Прочие произведения»…) скрыт настройкой. */
fun isHiddenSection(title: String): Boolean {
    val t = title.lowercase().trim()
    return AppPrefs.hiddenTypes.any { k -> SECTION_PREFIX[k]?.let { t.startsWith(it) } == true }
}

/** Произведение этого типа не показываем (всегда или по настройке пользователя). */
fun isHiddenType(type: String?): Boolean {
    val t = type?.lowercase()?.trim() ?: return false
    return t in ALWAYS_HIDDEN_TYPES || AppPrefs.hiddenTypes.any { t == it || t.contains(it) }
}

/** Название без кириллицы, но с латиницей (или другим алфавитом) — книга не издана на русском. */
fun isForeignTitle(title: String): Boolean {
    val letters = title.filter { it.isLetter() }
    if (letters.isEmpty()) return false
    return letters.none { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
}

/** «Межавторский цикл» и подобные служебные авторы FantLab. */
fun isPseudoAuthor(p: Person): Boolean =
    p.id == "fl-10" || p.name.contains("межавторск", ignoreCase = true)

fun isMinor(type: String?): Boolean {
    val t = type?.lowercase() ?: return false
    return t.contains("рассказ") || t in MINOR_TYPES
}

/**
 * Книги цикла по порядку: без вложенных подциклов-контейнеров и без мелких форм
 * (рассказы, статьи). Если цикл целиком из рассказов — оставляем их.
 */
fun cycleBooks(children: List<CycleItem>): List<CycleItem> {
    // части и главы, вложенные в книгу (не в цикл), — не отдельные книги
    val top = mutableListOf<CycleItem>()
    var skipDeeperThan = Int.MAX_VALUE
    for (it in children) {
        if (it.deep > skipDeeperThan) continue
        skipDeeperThan = if (it.book.isCycle) Int.MAX_VALUE else it.deep
        top += it
    }
    val leaves = top.filter { !it.book.isCycle && !isHiddenType(it.book.workType) }.distinctBy { it.book.id }
    val major = leaves.filter { !isMinor(it.book.workType) }
    return major.ifEmpty { leaves }
}

data class WorkDetails(
    val book: Book,
    val parents: List<SeriesRef>,
    val children: List<CycleItem>,
)

/** Раздел библиографии. Для цикла cycleId задан, книги пронумерованы по порядку. */
data class AuthorSection(val title: String, val books: List<Book>, val cycleId: String? = null)

data class AuthorDetails(
    val id: String,
    val name: String,
    val nameOrig: String,
    val imageUrl: String?,
    val years: String,
    val sections: List<AuthorSection>,
    /** Имя в родительном падеже: «книги Вадима Панова». */
    val nameGenitive: String = name,
)

data class AuthorHit(val id: String, val name: String, val subtitle: String)

/** Книги, которые пользователь видел в поиске и списках: нужны, чтобы открыть карточку. */
object BookCache {
    private val map = HashMap<String, Book>()
    @Synchronized fun put(book: Book) {
        val old = map[book.id]
        // не затираем подробную карточку краткой из списка
        map[book.id] = if (old == null) book else book.copy(
            coverUrl = book.coverUrl ?: old.coverUrl,
            annotation = book.annotation.ifEmpty { old.annotation },
            genres = book.genres.ifEmpty { old.genres },
            seriesId = book.seriesId ?: old.seriesId,
            seriesName = book.seriesName ?: old.seriesName,
            communityRating = book.communityRating ?: old.communityRating,
            authors = book.authors.ifEmpty { old.authors },
        )
    }
    @Synchronized fun get(id: String): Book? = map[id]
}

/** Рекомендованная книга и почему она предложена. */
data class Recommendation(val book: Book, val reason: String, val score: Double)
