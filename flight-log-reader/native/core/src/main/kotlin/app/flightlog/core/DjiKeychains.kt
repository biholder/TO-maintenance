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

    /** [fatal] — повтор с другими параметрами не поможет (неверный API-ключ, нет сети). */
    class ApiException(message: String, val fatal: Boolean = false) : Exception(message)

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
            if (code == 401 || code == 403) throw ApiException("DJI отклонил API-ключ ($code). Проверьте ключ и что приложение активировано", fatal = true)
            if (code !in 200..299) throw ApiException("Сервер DJI ответил кодом $code" +
                (c.errorStream?.use { es -> ": " + es.readBytes().toString(Charsets.UTF_8).take(200) } ?: ""))
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseResponse(body)
        } catch (e: java.io.IOException) {
            throw ApiException("Нет связи с сервером DJI: ${e.message}", fatal = true)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Ключи для лога: запрос с «приложением» и версией из лога, проверка на первых
     * записях, а если не подошли — повтор с другими приложениями DJI (Fly, Pilot,
     * GO, SDK…) и версиями. Так же поступает эталонный CLI, только вручную
     * (--api-custom-department / --api-custom-version).
     * [onAttempt] — для отображения прогресса: номер попытки и описание.
     */
    fun fetchMatching(
        apiKey: String,
        log: DjiLog,
        endpoint: String = ENDPOINT,
        onAttempt: (Int, String) -> Unit = { _, _ -> },
    ): String {
        val departments = (listOf(log.department) + listOf(3, 7, 2, 1, 5, 8, 4, 6)).distinct()
        val versions = (listOf(log.auxVersion) + listOf(1, 0)).distinct()
        val tried = ArrayList<String>()
        var lastApiError: ApiException? = null
        var best: Pair<Float, String>? = null
        var attempt = 0
        for (v in versions) for (dep in departments) {
            if (attempt >= MAX_ATTEMPTS) break
            attempt++
            val label = "${DEPARTMENTS[dep] ?: "department $dep"}, версия $v"
            onAttempt(attempt, label)
            val keys = try {
                fetch(apiKey, log.keychainsRequestJson(dep, v), endpoint)
            } catch (e: ApiException) {
                // Неверный API-ключ или нет сети — дальше пробовать бессмысленно.
                if (e.fatal) throw e
                lastApiError = e
                tried += "$label: ${e.message}"
                continue
            }
            val fit = runCatching { log.keysFit(DjiMapper.parseKeychains(keys)) }.getOrDefault(0f)
            if (fit >= GOOD_FIT) return keys
            if (best == null || fit > best.first) best = fit to keys
            tried += "$label: подошло ${(fit * 100).toInt()}%"
        }
        // Ни один вариант не идеален — берём лучший, если он явно не случайный.
        best?.let { (fit, keys) -> if (fit >= MIN_FIT) return keys }
        throw ApiException(
            "Ключи DJI не подошли к логу ни в одном варианте запроса. ${log.diagnostics()}. Попытки: " +
                tried.joinToString("; ").ifEmpty { lastApiError?.message ?: "—" },
        )
    }

    private const val MAX_ATTEMPTS = 10
    /** С верными ключами расшифровывается почти всё; с неверными — доли процента. */
    private const val GOOD_FIT = 0.8f
    private const val MIN_FIT = 0.5f

    private val DEPARTMENTS = mapOf(
        1 to "SDK", 2 to "DJI GO", 3 to "DJI Fly", 4 to "Agras", 5 to "Terra", 6 to "DJI Goggles", 7 to "DJI Pilot", 8 to "GS Pro",
    )

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
