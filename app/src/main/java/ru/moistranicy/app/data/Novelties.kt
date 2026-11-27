package ru.moistranicy.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar

/**
 * Новинки для «Моего поиска»: 5 книг этого года (со значком NEW) у любимых авторов
 * и в отслеживаемых циклах. Набор случайный и меняется при каждом запуске приложения.
 */
object Novelties {
    var books by mutableStateOf<List<Book>?>(null)
        private set
    private var loading = false

    suspend fun load() {
        if (books != null || loading) return
        loading = true
        try {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val shelf = ShelfRepository.entries.value
            fun fresh(id: String) = shelf[id]?.status != ReadStatus.READ
            val pool = LinkedHashMap<String, Book>()

            // книги этого года из отслеживаемых циклов
            CycleRepository.cycles.value.values.forEach { c ->
                c.books.filter { it.published && it.year?.trim()?.toIntOrNull() == year && fresh(it.id) && !isHiddenType(it.type) }
                    .forEach { b ->
                        pool.putIfAbsent(
                            b.id,
                            Book(
                                id = b.id, title = b.title, authors = c.authors, coverUrl = b.coverUrl,
                                annotation = "", genres = emptyList(), year = b.year, language = null,
                                seriesId = c.id, seriesName = c.name, seriesNumber = null, workType = b.type,
                            ),
                        )
                    }
            }

            // книги этого года у любимых авторов (по числу прочитанного), каждый запуск — другие авторы
            val fav = shelf.values.filter { it.status == ReadStatus.READ }
                .flatMap { e -> e.book.authors.mapNotNull { a -> a.id?.takeIf { it.startsWith("fl-") } } }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(15).map { it.key }
                .shuffled().take(6)
            val fromAuthors = coroutineScope {
                fav.map { id ->
                    async {
                        runCatching { FantLab.author(id) }.getOrNull()?.let { d ->
                            d.sections.flatMap { it.books }.filter {
                                it.published && !it.isCycle && it.year?.trim()?.toIntOrNull() == year &&
                                    !isHiddenType(it.workType) && fresh(it.id)
                            }.map { b -> b.copy(authors = b.authors.ifEmpty { listOf(Person(id, d.name)) }) }
                        }.orEmpty()
                    }
                }.awaitAll().flatten()
            }
            fromAuthors.forEach { pool.putIfAbsent(it.id, it) }

            books = pool.values.shuffled().take(5)
        } finally {
            loading = false
        }
    }
}
