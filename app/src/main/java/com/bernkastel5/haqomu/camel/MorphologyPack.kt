package com.bernkastel5.haqomu.camel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Расположение и быстрая сводка подготовленной морфологической базы (meta, O(1)). */
object MorphologyPack {
    const val FILE_NAME = "camel_pack.sqlite"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    data class Status(
        val stems: String,
        val prefixes: String,
        val suffixes: String,
        val buildDate: String,
        val sizeBytes: Long,
    )

    fun status(context: Context): Status? {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun meta(key: String): String =
                    db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
                        if (c.moveToFirst()) c.getString(0) else ""
                    }
                Status(meta("stems"), meta("prefixes"), meta("suffixes"), meta("build_date"), f.length())
            }
        } catch (_: Exception) {
            null
        }
    }
}
