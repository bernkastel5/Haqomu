package com.bernkastel5.haqomu.pack

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись единого пака §4 (architecture.md): meta/entry/sense и, если движок умеет, sense_fts.
 * Общая для конвертеров Kaikki и Hans Wehr. Один файл на пак; при ошибке вызывающий
 * код удаляет файл. Индексы idx_entry_norm, idx_entry_root, idx_sense_entry — как в схеме.
 *
 * FTS-индекс **необязателен** (ADR-0009): системный SQLite в Android обычно собран без
 * FTS5 («no such module: fts5»), а попап ищет по `entry.head_norm` и FTS не использует.
 * Поэтому индекс создаётся только если движок его поддерживает (FTS5, иначе FTS4),
 * а в meta пишется ключ `fts` = `fts5` / `fts4` / `none` — по нему проверка импорта
 * понимает, чего ждать от файла. Пак без индекса полноценен: так же читается и ищется.
 */
class AndroidPackWriter(private val outPath: String) : AutoCloseable {

    private val db: SQLiteDatabase
    private val insEntry: SQLiteStatement
    private val insSense: SQLiteStatement
    private val insFts: SQLiteStatement?
    private var ftsKind = FTS_NONE
    private var entryCount = 0L
    private var senseCount = 0L
    private var finished = false

    init {
        File(outPath).delete()
        db = SQLiteDatabase.openOrCreateDatabase(outPath, null)
        for (stmt in SCHEMA_STATEMENTS) db.execSQL(stmt)
        ftsKind = createFts(db)
        db.beginTransaction()
        insEntry = db.compileStatement(
            "INSERT INTO entry(id, head, head_norm, root, pos, pattern, source) VALUES (?,?,?,?,?,?,?)"
        )
        insSense = db.compileStatement(
            "INSERT INTO sense(id, entry_id, ord, definition, gloss_en, example, tags) VALUES (?,?,?,?,?,?,?)"
        )
        insFts = if (ftsKind == FTS_NONE) null else db.compileStatement(
            "INSERT INTO sense_fts(rowid, definition, gloss_en) VALUES (?,?,?)"
        )
    }

    fun addEntry(id: Long, head: String, headNorm: String, root: String?, pos: String?, source: String) {
        insEntry.bindLong(1, id)
        insEntry.bindString(2, head)
        insEntry.bindString(3, headNorm)
        if (root.isNullOrBlank()) insEntry.bindNull(4) else insEntry.bindString(4, root)
        if (pos.isNullOrBlank()) insEntry.bindNull(5) else insEntry.bindString(5, pos)
        insEntry.bindNull(6)
        insEntry.bindString(7, source)
        insEntry.executeInsert()
        entryCount += 1
    }

    fun addSense(id: Long, entryId: Long, ord: Int, definition: String, glossEn: String?, example: String?, tags: String?) {
        insSense.bindLong(1, id)
        insSense.bindLong(2, entryId)
        insSense.bindLong(3, ord.toLong())
        insSense.bindString(4, definition)
        if (glossEn.isNullOrBlank()) insSense.bindNull(5) else insSense.bindString(5, glossEn)
        if (example.isNullOrBlank()) insSense.bindNull(6) else insSense.bindString(6, example)
        if (tags.isNullOrBlank()) insSense.bindNull(7) else insSense.bindString(7, tags)
        insSense.executeInsert()
        // Индекс — ускоритель, не источник данных: без него пак остаётся целым.
        insFts?.let { stmt ->
            stmt.bindLong(1, id)
            stmt.bindString(2, definition)
            if (glossEn.isNullOrBlank()) stmt.bindNull(3) else stmt.bindString(3, glossEn)
            stmt.executeInsert()
        }
        senseCount += 1
    }

    /** Пишет meta (entry_count считает сам), коммитит, VACUUM, закрывает. Возвращает entry_count. */
    fun finish(source: String, license: String, langFrom: String, langTo: String): Long {
        val buildDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val meta = linkedMapOf(
            "schema_version" to "1",
            "source" to source,
            "license" to license,
            "build_date" to buildDate,
            "entry_count" to entryCount.toString(),
            "lang_from" to langFrom,
            "lang_to" to langTo,
            "fts" to ftsKind,
        )
        for ((k, v) in meta) {
            db.execSQL("INSERT INTO meta VALUES (?,?)", arrayOf(k, v))
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        db.execSQL("VACUUM")
        finished = true
        return entryCount
    }

    /** Число смыслов, записанных на данный момент (для отчёта). */
    fun sensesWritten(): Long = senseCount

    override fun close() {
        insEntry.close()
        insSense.close()
        insFts?.close()
        if (!finished) {
            try {
                db.endTransaction()
            } catch (_: Exception) {
            }
        }
        db.close()
    }

    companion object {
        private val SCHEMA_STATEMENTS = listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
            "CREATE TABLE entry (id INTEGER PRIMARY KEY, head TEXT NOT NULL, head_norm TEXT NOT NULL, root TEXT, pos TEXT, pattern TEXT, source TEXT)",
            "CREATE INDEX idx_entry_norm ON entry(head_norm)",
            "CREATE INDEX idx_entry_root ON entry(root)",
            "CREATE TABLE sense (id INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL REFERENCES entry(id), ord INTEGER NOT NULL, definition TEXT NOT NULL, gloss_en TEXT, example TEXT, tags TEXT)",
            "CREATE INDEX idx_sense_entry ON sense(entry_id)",
        )

        const val FTS_NONE = "none"
        private const val FTS5 = "fts5"
        private const val FTS4 = "fts4"

        /**
         * Пробует завести внешний FTS-индекс по таблице sense: сначала FTS5 (как в архитектуре),
         * затем FTS4 (есть в системном SQLite почти везде). Возвращает вид индекса для meta.
         *
         * Внешний контент (`content='sense'`) в обоих случаях означает, что копии определений
         * в индексе нет — пак от этого не тяжелеет, а поиск при необходимости идёт по sense.
         */
        private fun createFts(db: SQLiteDatabase): String {
            val attempts = listOf(
                FTS5 to "CREATE VIRTUAL TABLE sense_fts USING fts5(definition, gloss_en, content='sense', content_rowid='id')",
                FTS4 to "CREATE VIRTUAL TABLE sense_fts USING fts4(definition, gloss_en, content='sense')",
            )
            for ((kind, sql) in attempts) {
                try {
                    db.execSQL(sql)
                    return kind
                } catch (_: Exception) {
                    // движок не умеет такой модуль — пробуем следующий вариант
                }
            }
            return FTS_NONE
        }

        /**
         * Пост-проверка готового пака перед установкой: обязан пройти тот же PackChecks,
         * что и импорт из файла. Возвращает отчёт; вызывающий код решает (устанавливать или нет).
         */
        fun verifyBuilt(path: String): PackReport =
            AndroidSQLitePackSource.open(File(path)).use { PackChecks.verify(it) }
    }
}
