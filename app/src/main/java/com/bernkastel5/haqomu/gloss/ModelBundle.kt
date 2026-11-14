package com.bernkastel5.haqomu.gloss

import java.io.File
import java.util.zip.ZipFile

/** Чем закончилась проверка бандла модели. */
sealed interface BundleCheck {
    /** Бандл годен: размер, состав, манифест, словарь токенизатора. */
    data class Ok(val meta: BundleMeta) : BundleCheck

    /** Бандл не годен: [reason] — человекочитаемая причина для экрана загрузки. */
    data class Failed(val reason: String) : BundleCheck
}

/**
 * Манифест бандла `model.json` рядом с `model.onnx` и `vocab.txt`.
 *
 * Формат — наш, а не Hugging Face config.json: нужны только те поля, которые
 * приложение реально читает (имя для показа, размер, длина входа, флаги токенизатора,
 * имена входов и выхода). Лишнее не храним: оно стареет быстрее модели.
 */
data class BundleMeta(
    /** Человекочитаемое имя: «ArabGlossBERT int8». */
    val model: String,
    /** Версия бандла для показа («1»). */
    val version: Int = 1,
    /** Лицензия данных и весов — обязательна к показу (CC BY-SA 4.0). */
    val license: String = "CC BY-SA 4.0",
    /** Источник весов для атрибуции. */
    val source: String = "SinaLab/ArabGlossBERT",
    /** Байт в бандле или -1, если не указано. */
    val sizeBytes: Long = -1L,
    /** Максимальная длина входа токенизатора. */
    val maxLength: Int = 192,
    val lowercase: Boolean = false,
    val stripAccents: Boolean = true,
    /** Имена входов ONNX-графа. */
    val inputIds: String = "input_ids",
    val attentionMask: String = "attention_mask",
    val tokenTypeIds: String = "token_type_ids",
    /** Имя выхода: логиты на 2 класса. */
    val logits: String = "logits",
    /** Сколько классов на выходе (2 — «не подходит»/«подходит»). */
    val labels: Int = 2,
    /** Порог разрыва вероятностей; по умолчанию продуктовый 0.3. */
    val threshold: Double = 0.3,
)

/**
 * Проверка бандла модели (Ф13): архив или папка, в которой лежат `model.onnx`,
 * `vocab.txt` и `model.json`. **Модель не вшивается в APK и не раздаётся с нашей
 * стороны** (TZ §5.1, handoff §5.1: CC BY-SA 4.0, скачивается по кнопке).
 *
 * Проверяется до установки, без загрузки графа в память:
 *  1. архивы/файлы на месте и непустые;
 *  2. в zip есть все три файла, нет «zip-бомбы» (распакованный размер ограничен);
 *  3. манифест разбирается, версия понятна, `labels == 2`, `maxLength` в разумных пределах;
 *  4. в `vocab.txt` есть служебные токены `[UNK]`, `[CLS]`, `[SEP]`, `[PAD]`.
 *
 * Формат намеренно жёсткий: непонятный бандл отклоняется с причиной, а не падает
 * в рантайме посреди попапа.
 */
object ModelBundle {

    const val FILE_MODEL = "model.onnx"
    const val FILE_VOCAB = "vocab.txt"
    const val FILE_MANIFEST = "model.json"
    val REQUIRED = listOf(FILE_MODEL, FILE_VOCAB, FILE_MANIFEST)

    /** Потолок распакованного размера: int8 ~135 МБ, fp16 ~270 МБ, запас на словарь. */
    const val MAX_UNCOMPRESSED_BYTES = 400L * 1024 * 1024

    /** Потолок `maxLength`: 512 — предел BERT-позиций. */
    const val MAX_LENGTH_LIMIT = 512

    /**
     * Разбор манифеста. Допускается сокращённый вид `{"model": "...", ...}`:
     * отсутствующие поля берут значения по умолчанию. Неизвестные — игнорируются.
     */
    fun parseManifest(json: String): BundleCheck {
        val meta = try {
            BundleJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return BundleCheck.Failed("манифест не разобран: ${e.message}")
        }
        if (meta.model.isBlank()) return BundleCheck.Failed("в манифесте нет имени модели")
        if (meta.version < 1) return BundleCheck.Failed("версия бандла должна быть ≥ 1")
        if (meta.labels != 2) return BundleCheck.Failed("нужен классификатор на 2 класса, в манифесте ${meta.labels}")
        if (meta.maxLength !in 16..MAX_LENGTH_LIMIT) {
            return BundleCheck.Failed("maxLength вне 16..$MAX_LENGTH_LIMIT: ${meta.maxLength}")
        }
        if (meta.threshold < 0.0 || meta.threshold > 1.0) {
            return BundleCheck.Failed("порог вероятности вне 0..1: ${meta.threshold}")
        }
        if (meta.sizeBytes > MAX_UNCOMPRESSED_BYTES) {
            return BundleCheck.Failed("модель больше допустимого: ${meta.sizeBytes} байт")
        }
        return BundleCheck.Ok(meta)
    }

    /**
     * Проверка содержимого архива по списку записей и их размерам.
     * [entries] — имя → распакованный размер (для zip; для папки — размер файла).
     */
    fun checkEntries(entries: Map<String, Long>): BundleCheck.Failed? {
        for (name in REQUIRED) {
            val size = entries[name] ?: return BundleCheck.Failed("в бандле нет $name")
            if (size <= 0L) return BundleCheck.Failed("$name пустой")
        }
        val total = entries.values.sumOf { it.coerceAtLeast(0L) }
        if (total > MAX_UNCOMPRESSED_BYTES) {
            return BundleCheck.Failed("бандл распаковывается в ${total / 1048576} МБ — больше допустимого")
        }
        if (entries[FILE_VOCAB]!! > 32L * 1024 * 1024) {
            return BundleCheck.Failed("vocab.txt подозрительно большой")
        }
        return null
    }

    /** Проверка словаря токенизатора: служебные токены на месте, размер разумен. */
    fun checkVocab(vocabText: String): BundleCheck.Failed? {
        if (vocabText.isBlank()) return BundleCheck.Failed("vocab.txt пустой")
        val tokens = vocabText.lineSequence().map { it.trim('\r') }.toHashSet()
        for (special in listOf(BertTokenizer.UNK, BertTokenizer.CLS, BertTokenizer.SEP, BertTokenizer.PAD)) {
            if (special !in tokens) return BundleCheck.Failed("в vocab.txt нет $special")
        }
        if (tokens.size < 100) return BundleCheck.Failed("vocab.txt слишком короткий: ${tokens.size} токенов")
        return null
    }

    /** Полная проверка архива. Файл не загружается в память целиком — только оглавление. */
    fun checkZip(zip: File): BundleCheck {
        if (!zip.isFile || zip.length() == 0L) return BundleCheck.Failed("файл модели пустой или не найден")
        val entries = try {
            ZipFile(zip).use { zf ->
                val map = HashMap<String, Long>()
                val enumeration = zf.entries()
                while (enumeration.hasMoreElements()) {
                    val e = enumeration.nextElement()
                    if (!e.isDirectory) map[e.name] = e.size
                }
                map
            }
        } catch (e: Exception) {
            return BundleCheck.Failed("архив не читается: ${e.message ?: e.javaClass.simpleName}")
        }
        checkEntries(entries)?.let { return it }
        return try {
            ZipFile(zip).use { zf ->
                val manifest = zf.getInputStream(zf.getEntry(FILE_MANIFEST))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val meta = when (val parsed = parseManifest(manifest)) {
                    is BundleCheck.Failed -> return parsed
                    is BundleCheck.Ok -> parsed.meta
                }
                val vocab = zf.getInputStream(zf.getEntry(FILE_VOCAB))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                checkVocab(vocab)?.let { return it }
                BundleCheck.Ok(meta)
            }
        } catch (e: Exception) {
            BundleCheck.Failed("бандл не прочитан: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
