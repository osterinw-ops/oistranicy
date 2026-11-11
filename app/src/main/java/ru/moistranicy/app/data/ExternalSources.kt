package ru.moistranicy.app.data

import android.net.Uri

/** Популярные книжные сервисы в России: открываем их поиск в браузере. */
data class ExternalSource(val name: String, private val pattern: String) {
    fun url(query: String): String = pattern.replace("{q}", Uri.encode(query.trim()))

    /** Сайт сервиса (без пути): по нему берём его собственный значок. */
    val domain: String get() = Uri.parse(pattern.replace("{q}", "")).host.orEmpty()

    /** Значок сайта (favicon) крупного размера. */
    val iconUrl: String get() = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}

object ExternalSources {
    val all = listOf(
        ExternalSource("ЛитРес", "https://www.litres.ru/search/?q={q}"),
        ExternalSource("Яндекс Книги", "https://books.yandex.ru/search/all/{q}"),
        ExternalSource("MyBook", "https://mybook.ru/search/?q={q}"),
        ExternalSource("Читай-город", "https://www.chitai-gorod.ru/search?phrase={q}"),
        ExternalSource("LiveLib", "https://www.livelib.ru/find/books/{q}"),
        ExternalSource("Google Книги", "https://books.google.com/books?q={q}"),
    )

    /** Запрос «Название Автор» для поиска конкретной книги. */
    fun queryFor(book: Book): String =
        (book.title + " " + (book.authors.firstOrNull()?.name ?: "")).trim()
}
