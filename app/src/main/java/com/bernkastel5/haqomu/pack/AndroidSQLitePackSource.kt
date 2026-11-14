package com.bernkastel5.haqomu.pack

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * Чтение пака нативным SQLite в режиме read-only.
 * Вызывается для уже скопированного во временное место файла (см. ImportPacksScreen).
 */
class AndroidSQLitePackSource private constructor(private val db: SQLiteDatabase) : PackDataSource, Closeable {

    override fun tables(): Set<String> {
        val out = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun columns(table: String): Set<String> {
        val out = mutableSetOf<String>()
        if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return out
        db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { c ->
            while (c.moveToNext()) out.add(c.getString(1))
        }
        return out
    }

    override fun rowCount(table: String): Long {
        if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) throw IllegalArgumentException("bad table: $table")
        db.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    override fun metaValue(key: String): String? {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    override fun orphanSenses(): Long {
        db.rawQuery(
            "SELECT COUNT(*) FROM sense s LEFT JOIN entry e ON e.id = s.entry_id WHERE e.id IS NULL",
            emptyArray(),
        ).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    override fun emptyHeadNorms(): Long {
        db.rawQuery(
            "SELECT COUNT(*) FROM entry WHERE head_norm IS NULL OR head_norm = ''",
            emptyArray(),
        ).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    override fun close() = db.close()

    companion object {
        /** Открыть пак. Бросает при нечитаемом/битом файле: вызывающий код ловит и показывает ошибку. */
        fun open(file: File): AndroidSQLitePackSource {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            return AndroidSQLitePackSource(db)
        }
    }
}
