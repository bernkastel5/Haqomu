package com.bernkastel5.haqomu.update

/**
 * Разбор `BUILD.txt` из релиза и сравнение версий — чистая логика без Android.
 *
 * Формат пишет `tools/publish_apk.sh`:
 * ```
 * Haqomu — тестовая сборка
 * коммит: 98a2ad9
 * version_code: 12
 * version_name: 0.2.0
 * размер: 80M
 * sha256: 2b56…
 * ```
 * Сборки до появления версии писали только коммит и размер — такой файл тоже надо
 * понять (тогда обновления не предлагаем: сравнивать нечего, но коммит показать можно).
 */
object BuildTxt {

    data class Metadata(
        val versionCode: Int?,
        val versionName: String?,
        val commit: String?,
        val sizeBytes: Long?,
        val sha256: String?,
    )

    fun parse(text: String): Metadata {
        var code: Int? = null
        var name: String? = null
        var commit: String? = null
        var size: Long? = null
        var sha: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "version_code" -> code = value.toIntOrNull()
                "version_name" -> name = value.ifEmpty { null }
                "коммит", "commit" -> commit = value.substringAfterLast(' ').ifEmpty { null }
                "размер", "size" -> size = parseSize(value)
                "sha256" -> sha = value.ifEmpty { null }
            }
        }
        return Metadata(code, name, commit, size, sha)
    }

    /** «80M», «1.5G», «12K», «83883534» → байты; не удалось — null. */
    fun parseSize(text: String): Long? {
        val trimmed = text.trim().uppercase()
        if (trimmed.isEmpty()) return null
        val number = trimmed.takeWhile { it.isDigit() || it == '.' }
        val value = number.toDoubleOrNull() ?: return null
        val multiplier = when (trimmed.removePrefix(number).trim()) {
            "", "B" -> 1L
            "K", "KB" -> 1024L
            "M", "MB" -> 1024L * 1024L
            "G", "GB" -> 1024L * 1024L * 1024L
            else -> return null
        }
        return (value * multiplier).toLong()
    }

    /** Новая сборка новее установленной? Неизвестный номер — не обновление. */
    fun isNewer(installedCode: Int, availableCode: Int?): Boolean =
        availableCode != null && availableCode > installedCode
}
