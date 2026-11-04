package ru.moistranicy.app.data

enum class ReadStatus(val title: String) {
    READING("Читаю"),
    WANT("Хочу прочитать"),
    READ("Прочитал"),
    DROPPED("Бросил"),
}

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
data class CycleItem(val book: Book, val deep: Int)

data class WorkDetails(
    val book: Book,
    val parents: List<SeriesRef>,
    val children: List<CycleItem>,
)

data class AuthorSection(val title: String, val books: List<Book>)

data class AuthorDetails(
    val id: String,
    val name: String,
    val nameOrig: String,
    val imageUrl: String?,
    val years: String,
    val sections: List<AuthorSection>,
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
