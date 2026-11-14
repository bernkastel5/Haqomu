package com.bernkastel5.haqomu.pack

import android.content.Context
import java.io.File

/**
 * Установка собранного на устройстве пака в приватную папку packs/:
 * сначала PackChecks (§4), затем атомарная подмена файла. Исходник собирался
 * во временной папке кэша — при любой ошибке целевой файл не трогаем.
 */
object PackInstaller {

    data class InstallResult(
        val ok: Boolean,
        val report: PackReport? = null,   // отчёт PackChecks для собранного файла
        val error: String? = null,
        val destPath: String? = null,
    )

    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    fun install(context: Context, builtFile: File, destName: String): InstallResult {
        val report = try {
            AndroidPackWriter.verifyBuilt(builtFile.absolutePath)
        } catch (e: Exception) {
            return InstallResult(false, error = "проверка не выполнена: ${e.message}")
        }
        if (!report.passed) {
            val failed = report.checks.filter { !it.ok }.joinToString(", ") { it.id }
            return InstallResult(false, report = report, error = "проверка не пройдена: $failed")
        }
        val dir = packsDir(context)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            return InstallResult(false, report = report, error = "не удалось создать каталог packs")
        }
        val dest = File(dir, destName)
        val tmp = File(dir, "$destName.tmp")
        return try {
            builtFile.copyTo(tmp, overwrite = true)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            InstallResult(true, report = report, destPath = dest.absolutePath)
        } catch (e: Exception) {
            tmp.delete()
            InstallResult(false, report = report, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
