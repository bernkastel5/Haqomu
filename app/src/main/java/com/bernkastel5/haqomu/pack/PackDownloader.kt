package com.bernkastel5.haqomu.pack

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание источников для мастера установки (D-019): пользователь жмёт кнопку,
 * приложение само качает официальную копию у источника (CAMeL-Lab / GitHub raw / kaikki.org).
 * Ничего не раздаётся с нашей стороны. Только HTTPS, редиректы разрешены (GitHub releases).
 */
object PackDownloader {

    /** Результат скачивания: файл в кэше + реальный размер. */
    data class Download(val file: File, val bytes: Long)

    /**
     * Качает [url] в [dest]. [onProgress] получает (скачано байт, всего байт или -1 если неизвестно).
     * При любой ошибке частичный файл удаляется и выбрасывается IOException.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Download {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        val conn = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Haqomu-Android")
            c
        } catch (e: Exception) {
            throw IOException("не удалось подключиться к источнику: ${e.message}", e)
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("источник ответил HTTP $code")
            }
            val total = conn.contentLengthLong
            var copied = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return Download(dest, copied)
        } catch (e: Exception) {
            tmp.delete()
            if (e is IOException) throw e
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    /** URL официального zip calima-msa-r13 (camel-tools-data, релиз 2022.03.21, 40.5 МБ, GPL-2). */
    const val CAMEL_MSA_R13_ZIP =
        "https://github.com/CAMeL-Lab/camel-tools-data/releases/download/2022.03.21/morphology_db_calima-msa-r13-0.4.0.zip"

    /** Hans Wehr: GibreelAbdullah/HansWehrDictionary, assets/hanswehr.sqlite (raw, HEAD). */
    const val HANS_WEHR_SQLITE =
        "https://raw.githubusercontent.com/GibreelAbdullah/HansWehrDictionary/HEAD/assets/hanswehr.sqlite"

    /** Kaikki: Wiktionary ar→en, JSONL.gz (~72 МБ, 233k статей, CC BY-SA 4.0). */
    const val KAIKKI_AR_JSONL_GZ =
        "https://kaikki.org/dictionary/Arabic/kaikki.org-dictionary-Arabic.jsonl.gz"
}
