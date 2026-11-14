package com.bernkastel5.haqomu.pack

/**
 * Формат пака Haqomu (docs/architecture.md §4): один SQLite-файл на пак,
 * таблицы meta / entry / sense и FTS5-индекс sense_fts.
 *
 * Проверки pure (без Android API) — читают данные через [PackDataSource],
 * поэтому юнит-тесты гоняют их на синтетике. Android-реализация — AndroidSQLitePackSource.
 */
data class PackCheck(val id: String, val ok: Boolean, val detail: String? = null)

data class PackReport(
    val meta: Map<String, String>,
    val checks: List<PackCheck>,
    val fatalError: String? = null,
) {
    val passed: Boolean get() = fatalError == null && checks.all { it.ok }
    fun check(id: String): PackCheck? = checks.firstOrNull { it.id == id }
}

/** Источник данных пака: одна операция = один факт о файле. Реализации обязаны не падать. */
interface PackDataSource {
    /** Таблицы пакa (имена из sqlite_master, type='table'). Бросает при нечитаемом файле. */
    fun tables(): Set<String>
    /** Колонки таблицы; пустой набор, если таблицы нет. */
    fun columns(table: String): Set<String>
    /** COUNT(*) по таблице. Бросает, если таблицы нет или запрос невыполним (например FTS5 недоступен). */
    fun rowCount(table: String): Long
    fun metaValue(key: String): String?
    /** Число смыслов, чьё entry_id не существует в entry. */
    fun orphanSenses(): Long
    /** Число статей с пустым head_norm. */
    fun emptyHeadNorms(): Long
}

object PackChecks {
    const val SCHEMA_VERSION = "1"

    private val REQUIRED_TABLES = listOf("meta", "entry", "sense", "sense_fts")
    private val REQUIRED_COLUMNS = mapOf(
        "meta" to setOf("key", "value"),
        "entry" to setOf("id", "head", "head_norm"),
        "sense" to setOf("id", "entry_id", "ord", "definition"),
    )

    fun verify(ds: PackDataSource): PackReport {
        val checks = mutableListOf<PackCheck>()
        val meta = mutableMapOf<String, String>()
        var fatal: String? = null

        fun safe(id: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                checks.add(PackCheck(id, false, e.message ?: e.javaClass.simpleName))
            }
        }

        // 1. Файл вообще читается и похож на SQLite
        val tables = try {
            ds.tables()
        } catch (e: Exception) {
            return PackReport(meta, listOf(PackCheck("readable", false, e.message ?: "unreadable")), fatalError = e.message)
        }
        checks.add(PackCheck("readable", true))

        // 2. Обязательные таблицы
        for (t in REQUIRED_TABLES) {
            checks.add(PackCheck("table:$t", tables.contains(t)))
        }
        if (!tables.containsAll(setOf("meta", "entry", "sense"))) {
            return PackReport(meta, checks, fatalError = "not a Haqomu pack (missing core tables)")
        }

        // 3. Обязательные колонки
        for ((table, cols) in REQUIRED_COLUMNS) {
            val have = ds.columns(table)
            val missing = cols - have
            checks.add(
                PackCheck(
                    "columns:$table",
                    missing.isEmpty(),
                    if (missing.isEmpty()) null else "missing: ${missing.sorted().joinToString(", ")}",
                )
            )
        }

        // 4. meta: обязательные ключи
        fun metaVal(key: String): String? = runCatching { ds.metaValue(key) }.getOrNull()
        for (key in listOf("schema_version", "source", "license", "build_date", "entry_count")) {
            val v = metaVal(key)?.trim()
            meta[key] = v ?: ""
            checks.add(PackCheck("meta:$key", !v.isNullOrEmpty()))
        }
        // lang_from/lang_to (architecture §4) или language_from/language_to (TZ §5.2) — принимаем оба
        val langFrom = metaVal("lang_from")?.trim()?.ifEmpty { null } ?: metaVal("language_from")?.trim()
        val langTo = metaVal("lang_to")?.trim()?.ifEmpty { null } ?: metaVal("language_to")?.trim()
        if (langFrom != null) meta["lang_from"] = langFrom
        if (langTo != null) meta["lang_to"] = langTo
        checks.add(PackCheck("meta:lang_pair", !langFrom.isNullOrEmpty() && !langTo.isNullOrEmpty()))

        // 5. schema_version == 1
        checks.add(PackCheck("schema_version", meta["schema_version"] == SCHEMA_VERSION, "got ${meta["schema_version"]}"))

        // 6. Счётчики сходятся: meta.entry_count == COUNT(entry)
        val entryCount = runCatching { ds.rowCount("entry") }.getOrNull()
        val senseCount = runCatching { ds.rowCount("sense") }.getOrNull()
        val declared = meta["entry_count"]?.toLongOrNull()
        if (entryCount != null) meta["actual_entry_count"] = entryCount.toString()
        if (senseCount != null) meta["sense_count"] = senseCount.toString()
        checks.add(PackCheck("entry_count_present", declared != null))
        checks.add(
            PackCheck(
                "entry_count_match",
                declared != null && entryCount != null && declared == entryCount,
                "meta says ${declared ?: "?"}, table has ${entryCount ?: "?"}",
            )
        )
        checks.add(PackCheck("entry_nonempty", (entryCount ?: 0) > 0))
        checks.add(PackCheck("senses_nonempty", (senseCount ?: 0) > 0))

        // 7. Целостность связей
        safe("orphan_senses") {
            val orphans = ds.orphanSenses()
            checks.add(PackCheck("orphan_senses", orphans == 0L, if (orphans == 0L) null else "$orphans sense rows reference missing entry"))
        }
        safe("head_norm_filled") {
            val empty = ds.emptyHeadNorms()
            checks.add(PackCheck("head_norm_filled", empty == 0L, if (empty == 0L) null else "$empty entries with empty head_norm"))
        }

        // 8. FTS5 работает: запрос к sense_fts падает, если модуль FTS5 недоступен
        safe("fts5_available") {
            val n = ds.rowCount("sense_fts")
            checks.add(PackCheck("fts5_available", n >= 0))
        }

        return PackReport(meta, checks)
    }
}
