package ru.moistranicy.app.data

/**
 * Подсказки при опечатках в поиске: «Возможно, вы имели в виду …».
 * FantLab ищет только по целым словам, поэтому варианты строим сами.
 */
object Typos {
    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    /** Текст, набранный в английской раскладке вместо русской (ghbdtn → привет). */
    fun fixLayout(q: String): String? {
        if (q.none { it in 'a'..'z' || it in 'A'..'Z' }) return null
        val converted = q.map { ch ->
            val i = EN.indexOf(ch.lowercaseChar())
            if (i >= 0) RU[i] else ch
        }.joinToString("")
        return converted.takeIf { it != q && it.any { c -> c in 'а'..'я' } }
    }

    /** Правдоподобные исправления типичных русских опечаток (по убыванию вероятности). */
    fun variants(q: String): List<String> {
        val s = q.lowercase().trim()
        val out = LinkedHashSet<String>()
        fixLayout(s)?.let { out += it }
        if ('ё' in s) out += s.replace('ё', 'е')
        // пропущенный мягкий знак: лукяненко → лукьяненко, обявление → объявление
        Regex("([бвгджзклмнпрстфхцчшщ])([яюеё])").findAll(s).forEach { m ->
            out += s.substring(0, m.range.first + 1) + "ь" + s.substring(m.range.first + 1)
        }
        // лишний мягкий знак
        s.indices.filter { s[it] == 'ь' }.forEach { out += s.removeRange(it, it + 1) }
        // сдвоенные буквы: лишние и пропущенные
        Regex("(.)\\1").findAll(s).forEach { m -> out += s.removeRange(m.range.first, m.range.first + 1) }
        Regex("([нлсмрт])").findAll(s).forEach { m ->
            out += s.substring(0, m.range.first + 1) + m.value + s.substring(m.range.first + 1)
        }
        // безударные гласные и похожие буквы
        val swaps = listOf('о' to 'а', 'а' to 'о', 'е' to 'и', 'и' to 'е', 'й' to 'и', 'и' to 'й', 'ш' to 'щ', 'щ' to 'ш', 'е' to 'э', 'э' to 'е')
        for ((a, b) in swaps) {
            s.indices.filter { s[it] == a }.forEach { i -> out += s.substring(0, i) + b + s.substring(i + 1) }
        }
        out.remove(s)
        return out.toList()
    }

    /** Расстояние Левенштейна. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun tokens(s: String) = s.lowercase().replace('ё', 'е').split(Regex("[\\s,.\\-]+")).filter { it.length >= 2 }

    /**
     * Насколько имя похоже на запрос: для каждого слова запроса ищем ближайшее слово имени.
     * Возвращает суммарное расстояние или null, если не похоже.
     */
    fun nameScore(query: String, name: String): Int? {
        val q = tokens(query)
        val n = tokens(name)
        if (q.isEmpty() || n.isEmpty()) return null
        var total = 0
        for (w in q) {
            val best = n.minOf { distance(w, it) }
            val allowed = when {
                w.length <= 4 -> 1
                w.length <= 7 -> 2
                else -> 3
            }
            if (best > allowed) return null
            total += best
        }
        return total
    }

    /** Ближайшие известные имена авторов (для подсказок при наборе и «вы имели в виду»). */
    fun suggestKnown(query: String, limit: Int = 5): List<Pair<String, String?>> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val ql = q.lowercase().replace('ё', 'е')
        return KnownAuthors.snapshot().entries.mapNotNull { (name, id) ->
            val nl = name.lowercase().replace('ё', 'е')
            val prefix = tokens(nl).any { it.startsWith(ql) } || nl.startsWith(ql)
            val score = if (prefix) 0 else nameScore(q, name)
            score?.let { Triple(name, id, it) }
        }.sortedWith(compareBy<Triple<String, String?, Int>> { it.third }.thenBy { it.first.length })
            .take(limit).map { it.first to it.second }
    }

    /**
     * Ищет исправление, если поиск ничего не дал:
     * сначала среди известных авторов, затем пробует варианты опечаток на FantLab,
     * в конце — Google Книги (там поиск устойчив к ошибкам).
     */
    suspend fun didYouMean(query: String): String? {
        val q = query.trim()
        if (q.length < 3) return null
        suggestKnown(q, 1).firstOrNull()?.let { (name, _) ->
            if (!name.equals(q, ignoreCase = true)) return name
        }
        for (v in variants(q).take(8)) {
            val works = runCatching { FantLab.searchWorks(v, 1) }.getOrNull()
            if (works != null && works.items.isNotEmpty()) {
                // если исправленное слово — фамилия автора, предложим имя целиком
                val author = works.items.flatMap { it.authors }.firstOrNull { nameScore(v, it.name) != null }
                return author?.name ?: v
            }
            val authors = runCatching { FantLab.searchAuthors(v, 1) }.getOrNull()
            if (authors != null && authors.items.isNotEmpty()) return authors.items.first().name
        }
        val gb = runCatching { GoogleBooks.search(q, 1) }.getOrNull()
        if (gb != null) {
            gb.items.flatMap { it.authors }.map { it.name }.distinct()
                .mapNotNull { n -> nameScore(q, n)?.let { n to it } }
                .filter { it.second > 0 }
                .minByOrNull { it.second }
                ?.let { return it.first }
        }
        return null
    }
}
