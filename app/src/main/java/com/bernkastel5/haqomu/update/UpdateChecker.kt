package com.bernkastel5.haqomu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения без магазина (Ф16, ADR-0010).
 *
 * Что делает APK заранее, чтобы обновление потом было в одно касание:
 *  - знает свой канал: GitHub Releases `apk-latest`, там же, где лежит тестовая сборка;
 *  - умеет сравнить versionCode установленной сборки с `BUILD.txt` из этого релиза;
 *  - умеет скачать APK, отдать его системному установщику через FileProvider
 *    (провайдер уже объявлен в манифесте), а на Android 8+ — попросить разрешение
 *    «Установка неизвестных приложений» ровно для себя, а не для всего подряд;
 *  - помнит, когда проверяли, и не дёргает сеть чаще раза в сутки.
 *
 * Данные пользователя (паки в filesDir, настройки, Anki-очередь) при обновлении «поверх»
 * остаются: файлы живут вне APK, а versionCode растёт — установщик просто заменяет код.
 * Поэтому никакой миграции при обновлении кода не требуется; схема паков версионируется
 * отдельно (meta.schema_version) и переживает смену версии приложения.
 */
object UpdateChecker {

    /** Постоянная ссылка релиза: имя файла всегда одно и то же. */
    const val RELEASE_TAG = "apk-latest"
    const val APK_NAME = "haqomu-debug.apk"
    const val META_NAME = "BUILD.txt"

    private const val OWNER_REPO = "bernkastel5/Haqomu"
    private const val APK_URL = "https://github.com/$OWNER_REPO/releases/download/$RELEASE_TAG/$APK_NAME"
    private const val META_URL = "https://github.com/$OWNER_REPO/releases/download/$RELEASE_TAG/$META_NAME"
    private const val RELEASE_PAGE = "https://github.com/$OWNER_REPO/releases/tag/$RELEASE_TAG"

    /** Не чаще одной проверки в сутки без явного запроса пользователя. */
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    data class Info(
        val currentCode: Int,
        val currentName: String,
        val availableCode: Int?,
        val availableText: String?,
        val commit: String?,
        val apkBytes: Long?,
    ) {
        val hasUpdate: Boolean get() = BuildTxt.isNewer(currentCode, availableCode)
    }

    sealed interface Result {
        data class Found(val info: Info) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    fun currentVersion(context: Context): Pair<Int, String> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        (info.versionCode) to (info.versionName ?: "")
    } catch (_: Exception) {
        0 to ""
    }

    /** Проверить обновление. Сеть — только при [force] или если сутки прошли. */
    fun check(context: Context, force: Boolean, prefs: PrefsBridge): Result {
        val now = System.currentTimeMillis()
        val (code, name) = currentVersion(context)
        if (!force && now - prefs.checkedAt() < CHECK_INTERVAL_MS) {
            return Result.UpToDate
        }
        val (metaCode, metaText, commit) = try {
            fetchMeta()
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        prefs.setCheckedAt(now)
        val info = Info(
            currentCode = code,
            currentName = name,
            availableCode = metaCode,
            availableText = metaText,
            commit = commit,
            apkBytes = null,
        )
        return if (info.hasUpdate) Result.Found(info) else Result.UpToDate
    }

    private fun fetchMeta(): Triple<Int?, String?, String?> {
        val body = httpGet(META_URL, maxBytes = 8 * 1024)
        val meta = BuildTxt.parse(body)
        return Triple(meta.versionCode, meta.versionName, meta.commit)
    }

    private fun httpGet(url: String, maxBytes: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain")
            // GitHub отдаёт asset только с редиректом на CDN; без UA иногда 403.
            setRequestProperty("User-Agent", "Haqomu-UpdateChecker")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { input ->
                val buffer = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = input.read(buffer, read, maxBytes - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Скачивает APK в кэш и отдаёт файл для установки. Блокирующий вызов — звать из IO. */
    fun downloadApk(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val connection = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Haqomu-UpdateChecker")
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, APK_NAME)
        val tmp = File(dir, "$APK_NAME.part")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (tmp.length() < 1024 * 1024) throw IllegalStateException("файл слишком мал")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Открыть системный установщик для скачанного APK.
     *
     * На Android 8+ установка из неизвестного источника требует разрешения для конкретного
     * приложения; если его нет — открываем экран настроек, а пользователь повторит установку.
     * Возвращает true, если экран установки был показан.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Совместимость с обновлением из прежней версии: если раньше сборка не писала
     * version_code в BUILD.txt, показываем хотя бы коммит и ссылку на релиз.
     */
    fun releasePage(): String = RELEASE_PAGE
}

/**
 * Тонкая обёртка над настройками «когда проверяли»: сам [UpdateChecker] не тянет
 * Android-зависимый HaqomuPrefs, чтобы разбор BUILD.txt можно было тестировать на JVM.
 */
interface PrefsBridge {
    fun checkedAt(): Long
    fun setCheckedAt(value: Long)
}
