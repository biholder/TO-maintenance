package app.flightlog.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Получение ключей AES для логов DJI v13+ через DJI Open API.
 *
 * В DJI отправляются только зашифрованные ключи из записей KeyStorage лога
 * (не данные полёта); в ответ приходят ключи AES, которые можно сохранить
 * рядом с логом и дальше открывать его без сети.
 *
 * API-ключ: developer.dji.com → Create App → тип «Open API» → SDK key.
 */
object DjiKeychains {
    const val ENDPOINT = "https://dev.dji.com/openapi/v1/flight-records/keychains"

    class ApiException(message: String) : Exception(message)

    /** POST запроса и разбор ответа; возвращает JSON-массив цепочек ключей для [DjiMapper.parseKeychains]. */
    fun fetch(apiKey: String, requestJson: String, endpoint: String = ENDPOINT): String {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Api-Key", apiKey.trim())
            c.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 403) throw ApiException("DJI отклонил API-ключ (403). Проверьте ключ и что приложение активировано")
            if (code !in 200..299) throw ApiException("Сервер DJI ответил кодом $code")
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseResponse(body)
        } catch (e: java.io.IOException) {
            throw ApiException("Нет связи с сервером DJI: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    /** Ответ DJI → массив цепочек ключей (поле data). */
    fun parseResponse(body: String): String {
        val root = MiniJson.parse(body) as? Map<*, *> ?: throw ApiException("Непонятный ответ DJI")
        val result = root["result"] as? Map<*, *>
        val code = (result?.get("code") as? Double)?.toInt() ?: 0
        if (code != 0) throw ApiException("DJI: ${result?.get("msg") ?: "ошибка $code"}")
        val data = root["data"] as? List<*> ?: throw ApiException("DJI не вернул ключи")
        return MiniJson.stringify(data)
    }
}
