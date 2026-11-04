package ru.moistranicy.app.data

import java.util.Locale

/**
 * Подбор рекомендаций по полке пользователя:
 *  1) следующая книга начатых циклов;
 *  2) похожие (по FantLab) на книги с высокой оценкой;
 *  3) лучшие непрочитанные книги любимых авторов.
 * Книги, уже стоящие на полке, не предлагаются.
 */
object Recommender {

    private fun fmt(r: Float) = String.format(Locale("ru"), "%.1f", r)

    suspend fun build(): List<Recommendation> {
        val shelf = ShelfRepository.entries.value
        val onShelf = shelf.keys
        val read = shelf.values.filter { it.status == ReadStatus.READ }
        val out = LinkedHashMap<String, Recommendation>()

        fun offer(r: Recommendation) {
            val b = r.book
            if (b.id in onShelf || b.isCycle || !b.published) return
            if (isMinor(b.workType)) return
            val old = out[b.id]
            if (old == null || old.score < r.score) out[b.id] = r
        }

        // 1. Продолжение начатых циклов
        for (c in CycleRepository.cycles.value.values) {
            if (c.dropped) continue
            val published = c.books.filter { it.published }
            val readCount = published.count { shelf[it.id]?.status == ReadStatus.READ }
            if (readCount == 0) continue
            val next = published.firstOrNull { shelf[it.id] == null } ?: continue
            val n = published.indexOf(next) + 1
            offer(
                Recommendation(
                    CycleRepository.bookOf(c, next).let { it.copy(coverUrl = it.coverUrl ?: next.coverUrl) },
                    "Следующая, ${n}-я книга цикла «${c.name}» — вы прочитали $readCount из ${published.size}",
                    100.0 + readCount,
                ),
            )
        }

        // 2. Похожие на понравившиеся
        val liked = read.filter { it.book.id.startsWith("fl-") }
            .sortedWith(compareByDescending<ShelfEntry> { it.rating ?: 0 }.thenByDescending { it.finishedAt ?: 0 })
            .take(8)
        for (e in liked) {
            val sims = runCatching { FantLab.similars(e.book.id) }.getOrNull() ?: continue
            val why = if (e.rating != null) "Похожа на «${e.book.title}», которую вы оценили на ${e.rating}"
            else "Похожа на «${e.book.title}», которую вы прочитали"
            sims.take(6).forEach { b ->
                val rating = b.communityRating
                offer(
                    Recommendation(
                        b,
                        why + (rating?.let { " · рейтинг FantLab ${fmt(it)}" } ?: ""),
                        50.0 + (e.rating ?: 6) * 3 + (rating ?: 0f),
                    ),
                )
            }
        }

        // 3. Любимые авторы
        val favAuthors = read.flatMap { e -> e.book.authors.filter { it.id?.startsWith("fl-") == true } }
            .groupBy { it.id!! }
            .entries.sortedByDescending { it.value.size }
            .take(3)
        for ((authorId, list) in favAuthors) {
            val d = runCatching { FantLab.author(authorId) }.getOrNull() ?: continue
            val count = list.size
            d.sections.flatMap { it.books }
                .filter { it.id !in onShelf && it.published && !it.isCycle && !isMinor(it.workType) }
                .distinctBy { it.id }
                .sortedByDescending { it.communityRating ?: 0f }
                .take(3)
                .forEach { b ->
                    val rating = b.communityRating
                    offer(
                        Recommendation(
                            b.copy(authors = b.authors.ifEmpty { listOf(Person(authorId, d.name)) }),
                            "Вы прочитали $count ${books(count)} ${d.nameGenitive}" +
                                (rating?.let { " · рейтинг FantLab ${fmt(it)}" } ?: ""),
                            40.0 + count * 2 + (rating ?: 0f),
                        ),
                    )
                }
        }

        return out.values.sortedByDescending { it.score }
    }

    private fun books(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "книгу"
            m10 in 2..4 && m100 !in 12..14 -> "книги"
            else -> "книг"
        }
    }
}
