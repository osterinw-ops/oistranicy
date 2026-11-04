package ru.moistranicy.app.data

enum class ReadStatus(val title: String) {
    READING("Читаю"),
    WANT("Хочу прочитать"),
    READ("Прочитал"),
    DROPPED("Бросил"),
}

data class Person(val id: String?, val name: String)

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
) {
    val authorsLine: String get() = authors.joinToString(", ") { it.name }
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

/** Книги, которые пользователь видел в поиске/списках: нужны, чтобы открыть карточку. */
object BookCache {
    private val map = HashMap<String, Book>()
    @Synchronized fun put(book: Book) { map[book.id] = book }
    @Synchronized fun get(id: String): Book? = map[id]
}
