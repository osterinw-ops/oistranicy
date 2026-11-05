package ru.moistranicy.app

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.moistranicy.app.data.AppPrefs
import ru.moistranicy.app.data.AuthorStats
import ru.moistranicy.app.data.CycleRepository
import ru.moistranicy.app.data.FantLab
import ru.moistranicy.app.data.KnownAuthors
import ru.moistranicy.app.data.ReadStatus
import ru.moistranicy.app.data.RecBlock
import ru.moistranicy.app.data.ShelfRepository
import java.io.File

/**
 * Только для отладочной сборки: наполняет полку примерами книг,
 * чтобы снять скриншоты для магазина. Запуск:
 * adb shell am start -n ru.moistranicy.app/.DemoSeedActivity
 * По окончании пишет files/seed_done (и id книг в files/seed_ids.txt).
 */
class DemoSeedActivity : Activity() {

    private data class Item(val query: String, val author: String, val status: ReadStatus, val rating: Int?, val key: String? = null)

    private val items = listOf(
        Item("Хоббит, или Туда и обратно", "Толкин", ReadStatus.READ, 10),
        Item("Братство Кольца", "Толкин", ReadStatus.READ, 10),
        Item("Две крепости", "Толкин", ReadStatus.READ, 9),
        Item("Возвращение короля", "Толкин", ReadStatus.READ, 10),
        Item("Пикник на обочине", "Стругацк", ReadStatus.READ, 10, key = "book"),
        Item("Трудно быть богом", "Стругацк", ReadStatus.READ, 9),
        Item("Понедельник начинается в субботу", "Стругацк", ReadStatus.READ, 9),
        Item("Ночной Дозор", "Лукьяненко", ReadStatus.READ, 9),
        Item("Дневной Дозор", "Лукьяненко", ReadStatus.READ, 8),
        Item("Сумеречный Дозор", "Лукьяненко", ReadStatus.READING, null),
        Item("Последнее желание", "Сапковский", ReadStatus.READ, 10),
        Item("Меч Предназначения", "Сапковский", ReadStatus.READ, 9),
        Item("Кровь эльфов", "Сапковский", ReadStatus.READING, null),
        Item("451 градус по Фаренгейту", "Брэдбери", ReadStatus.READ, 8),
        Item("Марсианские хроники", "Брэдбери", ReadStatus.READ, 9),
        Item("Дюна", "Герберт", ReadStatus.WANT, null),
        Item("Основание", "Азимов", ReadStatus.WANT, null),
        Item("Цвет волшебства", "Пратчетт", ReadStatus.WANT, null),
        Item("Гиперион", "Симмонс", ReadStatus.WANT, null, key = "author"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        ShelfRepository.init(applicationContext)
        CycleRepository.init(applicationContext)
        AuthorStats.init(applicationContext)
        KnownAuthors.init(applicationContext)
        RecBlock.init(applicationContext)
        val done = File(filesDir, "seed_done").also { it.delete() }
        val ids = StringBuilder()
        CoroutineScope(Dispatchers.IO).launch {
            val authorIds = mutableListOf<String>()
            for (it in items) {
                runCatching {
                    val hits = FantLab.searchWorks(it.query, 1).items
                    val hit = hits.firstOrNull { b -> b.authors.any { a -> a.name.contains(it.author) } && !b.isCycle }
                        ?: return@runCatching
                    val d = FantLab.work(hit.id)
                    ShelfRepository.setStatus(d.book, it.status)
                    it.rating?.let { r -> ShelfRepository.setRating(d.book.id, r) }
                    d.book.authors.mapNotNull { a -> a.id }.let { a -> authorIds += a; KnownAuthors.addAll(d.book.authors) }
                    d.book.seriesId?.let { s ->
                        runCatching { CycleRepository.ensureFollowed(s); CycleRepository.ensureCovers(s) }
                    }
                    it.key?.let { k ->
                        ids.append("$k=${d.book.id}\n")
                        if (k == "author") d.book.authors.firstOrNull()?.id?.let { a -> ids.append("authorpage=$a\n") }
                    }
                    if (it.key == "book") d.book.authors.firstOrNull()?.id?.let { a -> ids.append("authorpage2=$a\n") }
                }
            }
            runCatching { AuthorStats.ensure(authorIds.distinct()) }
            KnownAuthors.flush()
            delay(3000) // даём репозиториям дописать файлы
            File(filesDir, "seed_ids.txt").writeText(ids.toString())
            done.writeText("ok")
            runOnUiThread { finish() }
        }
    }
}
