package ru.moistranicy.app.data.reader

import ru.moistranicy.app.data.Book
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.ShelfRepository

/** Связь файла книги с полкой: находим книгу на FantLab, ставим «Читаю» и «Прочитал». */
object Linker {
    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Найти книгу на FantLab по названию и автору из файла. */
    suspend fun find(b: LibBook): Book? {
        val surname = b.author.trim().split(' ').lastOrNull().orEmpty()
        val queries = listOfNotNull("${b.title} $surname".trim(), b.title.takeIf { surname.isNotEmpty() })
        val t = norm(b.title)
        for (q in queries) {
            val hits = runCatching { FantLab.searchWorks(q, 1).items }.getOrNull() ?: continue
            val good = hits.filter { !it.isCycle }
            val byAuthor = if (surname.isEmpty()) good else good.filter { h -> h.authors.any { norm(it.name).contains(norm(surname)) } }
            (byAuthor.firstOrNull { norm(it.title) == t } ?: byAuthor.firstOrNull { norm(it.title).startsWith(t) || t.startsWith(norm(it.title)) })
                ?.let { return it }
        }
        return null
    }

    /** Книга файла, которой нет на FantLab: живёт на полке под своим id «file-…». */
    fun localBook(b: LibBook): Book = Book(
        id = "file-" + b.id,
        title = b.title,
        authors = b.author.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { ru.moistranicy.app.data.Person(null, it) },
        coverUrl = b.cover?.let { java.io.File(Library.dirOf(b.id), it).toURI().toString() },
        annotation = "",
        genres = emptyList(),
        year = null,
        language = null,
        seriesId = null,
        seriesName = b.series,
        seriesNumber = b.seriesNo,
        workType = "роман",
    )

    /** id книги на полке: найденной на FantLab, а если её там нет — своей, из файла. */
    private suspend fun linkOf(b: LibBook): Pair<String, Book?> {
        b.linkedId?.let { return it to null }
        val found = find(b)
        val book = found ?: localBook(b)
        Library.update(b.id) { it.copy(linkedId = book.id) }
        return book.id to book
    }

    /** При открытии: связать (если ещё нет) и поставить «Читаю», если книга не прочитана. */
    suspend fun onOpen(b: LibBook) {
        val (id, fresh) = linkOf(b)
        if (id.startsWith("file-")) {
            val st = ShelfRepository.get(id)?.status
            if (st == null) ShelfRepository.setStatus(fresh ?: localBook(b), ReadStatus.READING)
            return
        }
        // файл книги уже есть — значит, она вышла, даже если на FantLab «ожидается»
        ru.moistranicy.app.data.CycleRepository.markPublished(id)
        val st = ShelfRepository.get(id)?.status
        if (st == ReadStatus.READING || st == ReadStatus.READ) return
        val book = runCatching { FantLab.work(id).book }.getOrNull() ?: ru.moistranicy.app.data.BookCache.get(id) ?: return
        ShelfRepository.setStatus(book, ReadStatus.READING)
    }

    /** Дочитали: «Прочитал». Возвращает id книги на полке. */
    suspend fun markRead(b: LibBook): String? {
        val (id, fresh) = linkOf(b)
        val book = ShelfRepository.get(id)?.book ?: fresh
            ?: (if (id.startsWith("file-")) localBook(b) else runCatching { FantLab.work(id).book }.getOrNull())
            ?: return null
        ShelfRepository.setStatus(book, ReadStatus.READ)
        Library.update(b.id) { it.copy(finished = true) }
        return id
    }
}
