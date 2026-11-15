package com.bernkastel5.haqomu.gloss

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Хранилище опциональной модели разрешения смыслов (Ф13, D-014).
 *
 * Модель **не вшивается в APK** (135 МБ int8 / 270 МБ fp16): пользователь качает
 * бандл сам или указывает файл; приложение проверяет его ([ModelBundle]), распаковывает
 * в приватную папку `filesDir/model/` и удаляет одной кнопкой. Ничего не раздаётся
 * с нашей стороны, версии и лицензии — на экране «Модель смыслов».
 *
 * Установка атомарная: распаковка идёт в `filesDir/model.tmp`, рабочий каталог
 * подменяется только после успешной проверки. Битый бандл не оставляет после себя
 * полуустановленной модели.
 */
object ModelStore {

    /** Каталог установленной модели (внутри — model.onnx, vocab.txt, model.json). */
    const val DIR = "model"
    private const val TMP_DIR = "model.tmp"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    private fun tmpDir(context: Context): File = File(context.filesDir, TMP_DIR)

    /** Установлена ли модель: три обязательных файла и манифест на месте. */
    fun isInstalled(context: Context): Boolean {
        val d = dir(context)
        if (!d.isDirectory) return false
        return ModelBundle.REQUIRED.all { File(d, it).let { f -> f.isFile && f.length() > 0 } }
    }

    /** Манифест установленной модели (null — не установлена или манифест битый). */
    fun meta(context: Context): BundleMeta? {
        if (!isInstalled(context)) return null
        val text = try {
            File(dir(context), ModelBundle.FILE_MANIFEST).readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return (ModelBundle.parseManifest(text) as? BundleCheck.Ok)?.meta
    }

    /** Размер установленной модели на диске. */
    fun sizeBytes(context: Context): Long {
        val d = dir(context)
        if (!d.isDirectory) return 0L
        return d.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /**
     * Установка из архива бандла (`.zip` с `model.onnx`, `vocab.txt`, `model.json`;
     * допускается одна корневая папка внутри). [archive] — файл, выбранный пользователем.
     *
     * @return результат проверки: Ok — установлено, Failed — причина для показа.
     */
    fun installFromZip(context: Context, archive: File): BundleCheck {
        val check = ModelBundle.checkZip(archive)
        if (check is BundleCheck.Failed) return check

        val tmp = tmpDir(context)
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return BundleCheck.Failed("не удалось создать папку для модели")
        try {
            unzip(archive, tmp)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return BundleCheck.Failed("распаковка не удалась: ${e.message ?: e.javaClass.simpleName}")
        }
        // Архив мог быть собран с корневой папкой внутри — поднимаем файлы наверх.
        flatten(tmp)
        for (name in ModelBundle.REQUIRED) {
            val f = File(tmp, name)
            if (!f.isFile || f.length() == 0L) {
                tmp.deleteRecursively()
                return BundleCheck.Failed("в архиве нет $name")
            }
        }
        // Перепроверяем уже распакованное: манифест и словарь читаем с диска.
        return verifyAndSwap(context, tmp)
    }

    /**
     * Установка из папки, где пользователь держит бандл (например, распаковал сам
     * или положил `model.onnx` + `vocab.txt` + `model.json` в одну папку на карте).
     * Файлы копируются в приватную папку приложения: оригиналы не трогаем.
     */
    fun installFromDir(context: Context, source: File): BundleCheck {
        if (!source.isDirectory) return BundleCheck.Failed("это не папка")
        val tmp = tmpDir(context)
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return BundleCheck.Failed("не удалось создать папку для модели")
        for (name in ModelBundle.REQUIRED) {
            val from = File(source, name)
            if (!from.isFile || from.length() == 0L) {
                tmp.deleteRecursively()
                return BundleCheck.Failed("в папке нет $name")
            }
            from.copyTo(File(tmp, name), overwrite = true)
        }
        return verifyAndSwap(context, tmp)
    }

    /** Удаление модели (Ф13: «удаление» рядом с «загрузкой»). */
    fun remove(context: Context) {
        dir(context).deleteRecursively()
        tmpDir(context).deleteRecursively()
    }

    // ------------------------------------------------------------ внутреннее

    private fun swap(context: Context, tmp: File, meta: BundleMeta): BundleCheck {
        val target = dir(context)
        if (target.exists() && !target.deleteRecursively()) {
            tmp.deleteRecursively()
            return BundleCheck.Failed("не удалось заменить старую модель")
        }
        if (!tmp.renameTo(target)) {
            // renameTo не работает между разными ФС — копируем файлы по одному.
            target.mkdirs()
            for (name in ModelBundle.REQUIRED) {
                File(tmp, name).copyTo(File(target, name), overwrite = true)
            }
            tmp.deleteRecursively()
        }
        if (!isInstalled(context)) {
            target.deleteRecursively()
            return BundleCheck.Failed("модель установлена неполно")
        }
        return BundleCheck.Ok(meta)
    }

    /** Общая проверка распакованного каталога и подмена рабочей папки. */
    private fun verifyAndSwap(context: Context, tmp: File): BundleCheck {
        val manifest = try {
            String(File(tmp, ModelBundle.FILE_MANIFEST).readBytes(), Charsets.UTF_8)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return BundleCheck.Failed("манифест не прочитан: ${e.message}")
        }
        val parsed = when (val check = ModelBundle.parseManifest(manifest)) {
            is BundleCheck.Failed -> {
                tmp.deleteRecursively()
                return check
            }
            is BundleCheck.Ok -> check
        }
        val vocab = try {
            String(File(tmp, ModelBundle.FILE_VOCAB).readBytes(), Charsets.UTF_8)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return BundleCheck.Failed("словарь не прочитан: ${e.message}")
        }
        ModelBundle.checkVocab(vocab)?.let {
            tmp.deleteRecursively()
            return it
        }
        return swap(context, tmp, parsed.meta)
    }

    private fun unzip(archive: File, dest: File) {
        ZipFile(archive).use { zf ->
            val entries: List<ZipEntry> = zf.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name
                // Защита от путей с выходом из папки модели.
                if (name.contains("..") || name.startsWith("/")) continue
                val out = File(dest, name.substringAfterLast('/'))
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** Если архив вложил файлы в одну корневую папку, поднимаем их на уровень выше. */
    private fun flatten(dir: File) {
        if (ModelBundle.REQUIRED.all { File(dir, it).isFile }) return
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: return
        for (sub in subdirs) {
            if (ModelBundle.REQUIRED.all { File(sub, it).isFile }) {
                for (name in ModelBundle.REQUIRED) {
                    File(sub, name).copyTo(File(dir, name), overwrite = true)
                }
                return
            }
        }
    }
}
