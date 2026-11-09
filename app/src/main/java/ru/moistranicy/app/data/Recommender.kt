package ru.moistranicy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * Подбор рекомендаций по полке пользователя:
 *  1) похожие (по FantLab) на прочитанные книги с высокой оценкой;
 *  2) лучшие непрочитанные книги любимых авторов.
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
            if (RecBlock.isBlocked(b)) return
            if (isMinor(b.workType)) return
            val old = out[b.id]
            if (old == null || old.score < r.score) out[b.id] = r
        }

        // 1. Похожие на прочитанные и высоко оценённые
        val fl = read.filter { it.book.id.startsWith("fl-") }
        val high = fl.filter { (it.rating ?: 0) >= 8 }
        val liked = high.ifEmpty { fl.filter { (it.rating ?: 0) >= 7 } }.ifEmpty { fl }
            .sortedWith(compareByDescending<ShelfEntry> { it.rating ?: 0 }.thenByDescending { it.finishedAt ?: 0 })
            .take(10)
        for (e in liked) {
            val sims = runCatching { FantLab.similars(e.book.id) }.getOrNull() ?: continue
            val why = if (e.rating != null) "Похожа на «${e.book.title}», которую вы оценили на ${e.rating}"
            else "Похожа на «${e.book.title}», которую вы прочитали"
            sims.take(8).forEach { b ->
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

        // 2. Любимые авторы
        val favAuthors = read.flatMap { e -> e.book.authors.filter { it.id?.startsWith("fl-") == true } }
            .groupBy { it.id!! }
            .entries.sortedByDescending { it.value.size }
            .take(5)
        val year = noveltyYear()
        for ((authorId, list) in favAuthors) {
            val d = runCatching { FantLab.author(authorId) }.getOrNull() ?: continue
            val count = list.size
            // новинки года у любимых авторов — первая рекомендация должна быть свежей
            d.sections.flatMap { it.books }
                .filter { it.id !in onShelf && it.published && !it.isCycle && !isMinor(it.workType) && it.year?.toIntOrNull() == year }
                .distinctBy { it.id }
                .take(2)
                .forEach { b ->
                    offer(
                        Recommendation(
                            b.copy(authors = b.authors.ifEmpty { listOf(Person(authorId, d.name)) }),
                            "Новинка $year года · вы прочитали $count ${books(count)} ${d.nameGenitive}",
                            45.0 + count * 2 + (b.communityRating ?: 0f),
                        ),
                    )
                }
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

    /** Дополнительный набор для жанра: похожие на книги этого жанра с полки. Жанры уточнит [enrich]. */
    suspend fun forGenre(tag: String, sameGenre: (Book) -> Boolean): List<Recommendation> {
        val shelf = ShelfRepository.entries.value
        val sources = shelf.values
            .filter { it.status != ReadStatus.DROPPED && it.book.id.startsWith("fl-") && sameGenre(it.book) }
            .sortedWith(compareByDescending<ShelfEntry> { it.rating ?: 0 }.thenByDescending { it.updatedAt })
            .take(8)
        val out = LinkedHashMap<String, Recommendation>()
        for (e in sources) {
            val sims = runCatching { FantLab.similars(e.book.id) }.getOrNull() ?: continue
            sims.forEach { b ->
                if (b.id !in shelf && !b.isCycle && b.published && !isMinor(b.workType) && !RecBlock.isBlocked(b)) {
                    val rating = b.communityRating
                    val score = 45.0 + (e.rating ?: 6) + (rating ?: 0f)
                    if ((out[b.id]?.score ?: 0.0) < score) {
                        out[b.id] = Recommendation(
                            b,
                            "Жанр «$tag»: похожа на «${e.book.title}»" + (rating?.let { " · рейтинг FantLab ${fmt(it)}" } ?: ""),
                            score,
                        )
                    }
                }
            }
        }
        return out.values.sortedByDescending { it.score }
    }

    /** Догружает жанры и год рекомендованных книг (в списках FantLab их нет) порциями. */
    suspend fun enrich(list: List<Book>, onBatch: (List<Book>) -> Unit) {
        for (part in list.chunked(4)) {
            val loaded = coroutineScope {
                part.map { b -> async(Dispatchers.IO) { runCatching { FantLab.work(b.id).book }.getOrNull() } }.awaitAll()
            }.filterNotNull()
            if (loaded.isNotEmpty()) onBatch(loaded)
        }
    }

    /** Год «новинок»: текущий, а в январе — прошедший. */
    fun noveltyYear(): Int {
        val c = java.util.Calendar.getInstance()
        val y = c.get(java.util.Calendar.YEAR)
        return if (c.get(java.util.Calendar.MONTH) == java.util.Calendar.JANUARY) y - 1 else y
    }

    /** Ключи авторов, у которых пользователь прочитал хотя бы одну книгу. */
    fun readAuthorKeys(): Set<String> = ShelfRepository.entries.value.values
        .filter { it.status == ReadStatus.READ }
        .flatMap { e -> e.book.authors.map { RecBlock.authorKey(it) } }.toSet()

    /**
     * Порядок показа: первая книга — новинка года [noveltyYear] (если есть),
     * среди первых пяти — минимум две книги авторов, которых пользователь ещё не читал.
     */
    fun arrange(list: List<Recommendation>, readAuthors: Set<String>, year: Int = noveltyYear()): List<Recommendation> {
        if (list.size < 2) return list
        fun isNew(r: Recommendation) = r.book.authors.isNotEmpty() && r.book.authors.none { RecBlock.authorKey(it) in readAuthors }
        val rest = list.toMutableList()
        val fresh = rest.firstOrNull { it.book.year?.trim()?.toIntOrNull() == year }
        val pinned = if (fresh != null) { rest.remove(fresh); listOf(fresh) } else emptyList()
        val head = (pinned + rest.take(5 - pinned.size)).toMutableList()
        val tail = rest.drop(5 - pinned.size).toMutableList()
        while (head.count { isNew(it) } < 2) {
            val cand = tail.firstOrNull { isNew(it) } ?: break
            val victim = head.indices.lastOrNull { i -> !isNew(head[i]) && !(i == 0 && pinned.isNotEmpty()) } ?: break
            val old = head[victim]
            head[victim] = cand
            tail.remove(cand)
            tail.add(0, old)
        }
        // незнакомых авторов — не подряд в конце, а через одну: 2-я и 4-я позиции
        val newInHead = head.drop(pinned.size).filter { isNew(it) }
        val oldInHead = head.drop(pinned.size).filter { !isNew(it) }
        val mixed = mutableListOf<Recommendation>()
        val n = newInHead.toMutableList()
        val o = oldInHead.toMutableList()
        while (n.isNotEmpty() || o.isNotEmpty()) {
            val wantNew = (pinned.size + mixed.size) % 2 == 1
            mixed += if (wantNew && n.isNotEmpty()) n.removeAt(0) else if (o.isNotEmpty()) o.removeAt(0) else n.removeAt(0)
        }
        return pinned + mixed + tail
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
