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

    /** При открытии: связать (если ещё нет) и поставить «Читаю», если книга не прочитана. */
    suspend fun onOpen(b: LibBook) {
        val id = b.linkedId ?: find(b)?.also { found -> Library.update(b.id) { it.copy(linkedId = found.id) } }?.id ?: return
        val st = ShelfRepository.get(id)?.status
        if (st == ReadStatus.READING || st == ReadStatus.READ) return
        val book = runCatching { FantLab.work(id).book }.getOrNull() ?: ru.moistranicy.app.data.BookCache.get(id) ?: return
        ShelfRepository.setStatus(book, ReadStatus.READING)
    }

    /** Дочитали: «Прочитал». Возвращает id книги на полке. */
    suspend fun markRead(b: LibBook): String? {
        val id = b.linkedId ?: return null
        val book = ShelfRepository.get(id)?.book ?: runCatching { FantLab.work(id).book }.getOrNull() ?: return null
        ShelfRepository.setStatus(book, ReadStatus.READ)
        return id
    }
}
