package ru.moistranicy.app.data

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Короткое понятное описание ошибки для пользователя (без технических подробностей). */
fun friendlyError(e: Throwable?): String {
    var t: Throwable? = e
    // ищем исходную причину в цепочке
    while (t != null) {
        when (t) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                return "Нет связи с интернетом. Проверьте подключение и попробуйте ещё раз."
            is SocketTimeoutException -> return "Сервер долго не отвечает. Попробуйте позже."
            is SSLException -> return "Не удалось установить защищённое соединение. Попробуйте позже."
        }
        val msg = t.message.orEmpty()
        if (msg.contains("войдите заново")) return msg
        // 507 «Insufficient Storage» бывает только у Яндекс Диска; раньше «507» искали в любом тексте
        // ошибки (например, в ответе FantLab с числом 507 внутри) и ошибочно писали про нехватку места
        if (msg.startsWith("Яндекс Диск") && Regex("код 507\\b").containsMatchIn(msg)) return "На Яндекс Диске закончилось место."
        if (t is org.json.JSONException) return "FantLab прислал неполные или повреждённые данные. Попробуйте ещё раз чуть позже."
        if (Regex("код (5\\d\\d)").containsMatchIn(msg)) return "Сервис временно недоступен. Попробуйте позже."
        if (Regex("код 4(29|03)").containsMatchIn(msg)) return "Сервис ограничил запросы. Попробуйте чуть позже."
        if (t.cause === t) break
        t = t.cause
    }
    return if (e is IOException) "Не удалось связаться с сервером. Попробуйте ещё раз." else "Что-то пошло не так. Попробуйте ещё раз."
}
