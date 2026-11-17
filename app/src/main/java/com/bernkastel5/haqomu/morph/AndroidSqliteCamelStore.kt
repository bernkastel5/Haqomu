package com.bernkastel5.haqomu.morph

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Чтение пака `camel_pack.sqlite` (см. tools/build_camel_pack.py) нативным SQLite.
 * Файл готовится пользователем на устройстве или на ПК; в git и APK не входит.
 */
class AndroidSqliteCamelStore(path: String) : CamelStore, AutoCloseable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    /** Есть ли в паке колонка огласовки (паки до Ф15 её не имеют). */
    private val hasDiac: Boolean by lazy {
        try {
            db.rawQuery("SELECT stem_diac FROM stems LIMIT 1", null).use {
                it.columnCount > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun stems(stem: String): List<StemRow> {
        val columns = if (hasDiac) "cat, pos, lex, lex_logprob, root, stem_diac" else "cat, pos, lex, lex_logprob, root"
        db.rawQuery(
            "SELECT $columns FROM stems WHERE stem = ?",
            arrayOf(stem),
        ).use { c ->
            val out = mutableListOf<StemRow>()
            while (c.moveToNext()) {
                out.add(
                    StemRow(
                        cat = c.getString(0),
                        pos = c.getString(1),
                        lex = c.getString(2),
                        lexLogprob = c.getDouble(3),
                        root = c.getString(4),
                        diac = if (hasDiac) c.getString(5).orEmpty() else "",
                    )
                )
            }
            return out
        }
    }

    override fun prefixes(form: String): List<AffixRow> = affixes(form, "prefix")

    override fun suffixes(form: String): List<AffixRow> = affixes(form, "suffix")

    private fun affixes(form: String, kind: String): List<AffixRow> {
        db.rawQuery(
            "SELECT cat, feats FROM affixes WHERE form = ? AND kind = ?",
            arrayOf(form, kind),
        ).use { c ->
            val out = mutableListOf<AffixRow>()
            while (c.moveToNext()) {
                out.add(AffixRow(cat = c.getString(0), feats = parseFeats(c.getString(1))))
            }
            return out
        }
    }

    override fun compatAllows(kind: Char, a: String, b: String): Boolean {
        // В паке kind хранится парами («ab», «bc», «ac»), как их пишут
        // tools/build_camel_pack.py и CamelMorphologyParser, — см. PackCompatKey.
        val key = PackCompatKey.of(kind) ?: return false
        db.rawQuery(
            "SELECT 1 FROM compat WHERE kind = ? AND a = ? AND b = ? LIMIT 1",
            arrayOf(key, a, b),
        ).use { c -> return c.moveToFirst() }
    }

    override fun close() {
        db.close()
    }

    companion object {
        private fun parseFeats(json: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                out[key] = obj.getString(key)
            }
            return out
        }
    }
}
