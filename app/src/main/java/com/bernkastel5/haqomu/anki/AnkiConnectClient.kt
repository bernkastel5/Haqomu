package com.bernkastel5.haqomu.anki

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Опциональный путь для Anki на ПК (D-016): AnkiConnect (HTTP JSON-RPC, обычно порт 8765).
 * На телефоне localhost не достаёт ПК — пользователь указывает адрес машины с Anki
 * (в AnkiConnect webBindAddress должен слушать сеть, а не только loopback).
 * Ошибки возвращаются человекочитаемыми; при неудаче используйте экспорт .apkg.
 */
object AnkiConnectClient {

    data class Result(val ok: Boolean, val message: String)

    /** Тело запроса version (JSON-RPC, version=6). */
    fun versionBody(): String = """{"action":"version","version":6}"""

    /** Тело запроса addNote для нашей модели. */
    fun addNoteBody(
        deckName: String,
        card: PendingCard,
        tags: List<String> = listOf("haqomu"),
    ): String {
        val fields = Json.stringMap(
            listOf(
                AnkiModel.EF_EXPRESSION to card.expression,
                AnkiModel.EF_TASHKEEL to card.tashkeel,
                AnkiModel.EF_MEANING to card.meaning,
                AnkiModel.EF_SENTENCE to card.sentence,
                AnkiModel.EF_ROOT to card.root,
                AnkiModel.EF_DICTIONARY to card.dictionary,
                AnkiModel.EF_NOTES to card.notes,
            )
        )
        return """{"action":"addNote","version":6,"params":{"note":{""" +
            "\"deckName\":${Json.quoted(deckName)}," +
            "\"modelName\":${Json.quoted(AnkiModel.MODEL_NAME)}," +
            "\"fields\":$fields," +
            "\"tags\":${Json.stringList(tags)}," +
            "\"options\":{\"allowDuplicate\":false,\"duplicateScope\":\"deck\"}" +
            "}}}"
    }

    /** POST JSON-RPC. @throws IOException при сетевой ошибке. */
    fun post(url: String, body: String, timeoutMs: Int = 5000): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    /** Проверка доступности: result >= 6 — ок. */
    fun check(url: String): Result {
        return try {
            val (code, text) = post(url, versionBody(), timeoutMs = 2500)
            if (code !in 200..299) {
                Result(false, "HTTP $code")
            } else if (text.contains("\"error\":null") || Regex("\"result\":\\s*\\d+").containsMatchIn(text)) {
                Result(true, "")
            } else {
                Result(false, text.take(300))
            }
        } catch (e: IOException) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Добавить одну карточку; успех — когда в ответе error == null. */
    fun addNote(url: String, deckName: String, card: PendingCard): Result = try {
        val (code, text) = post(url, addNoteBody(deckName, card))
        when {
            code !in 200..299 -> Result(false, "HTTP $code: ${text.take(200)}")
            text.contains("\"error\":null") -> Result(true, "OK")
            else -> Result(false, text.take(300))
        }
    } catch (e: IOException) {
        Result(false, e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        Result(false, e.message ?: e.javaClass.simpleName)
    }
}
