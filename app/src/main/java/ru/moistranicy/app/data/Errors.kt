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
        if (Regex("код (5\\d\\d)").containsMatchIn(msg)) return "Сервис временно недоступен. Попробуйте позже."
        if (Regex("код 4(29|03)").containsMatchIn(msg)) return "Сервис ограничил запросы. Попробуйте чуть позже."
        if (msg.contains("507") || msg.contains("Insufficient", true)) return "На Яндекс Диске закончилось место."
        if (t.cause === t) break
        t = t.cause
    }
    return if (e is IOException) "Не удалось связаться с сервером. Попробуйте ещё раз." else "Что-то пошло не так. Попробуйте ещё раз."
}
