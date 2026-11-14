package com.bernkastel5.haqomu.camel

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java.nio.charset.StandardCharsets

data class CamelBuildResult(
    val linesRead: Long,
    val counts: Map<String, String>,
    val sizeBytes: Long,
)

/**
 * Потоковая запись calima в camel_pack.sqlite (та же схема, что tools/build_camel_pack.py).
 * Открытие: файл назначения пересоздаётся. Транзакция одна, VACUUM после коммита.
 */
class AndroidCamelPackWriter(private val outPath: String) : PackSink, Closeable {

    private val db: SQLiteDatabase
    private val insStem: SQLiteStatement
    private val insAffix: SQLiteStatement
    private val insCompat: SQLiteStatement
    private var counts: Map<String, String> = emptyMap()
    private var finished = false

    init {
        File(outPath).delete()
        db = SQLiteDatabase.openOrCreateDatabase(outPath, null)
        for (stmt in SCHEMA_STATEMENTS) db.execSQL(stmt)
        db.beginTransaction()
        insStem = db.compileStatement("INSERT INTO stems VALUES (?,?,?,?,?,?,?)")
        insAffix = db.compileStatement("INSERT INTO affixes VALUES (?,?,?,?)")
        insCompat = db.compileStatement("INSERT INTO compat VALUES (?,?,?)")
    }

    override fun onStem(entry: StemEntry) {
        insStem.bindString(1, entry.stem)
        insStem.bindString(2, entry.cat)
        insStem.bindString(3, entry.pos)
        insStem.bindString(4, entry.lex)
        insStem.bindDouble(5, entry.lexLogprob)
        if (entry.root.isBlank()) insStem.bindNull(6) else insStem.bindString(6, entry.root)
        if (entry.featsJson == null) insStem.bindNull(7) else insStem.bindString(7, entry.featsJson)
        insStem.executeInsert()
    }

    override fun onPrefix(entry: AffixEntry) = affix(entry)
    override fun onSuffix(entry: AffixEntry) = affix(entry)

    private fun affix(entry: AffixEntry) {
        insAffix.bindString(1, entry.form)
        insAffix.bindString(2, entry.kind)
        insAffix.bindString(3, entry.cat)
        insAffix.bindString(4, entry.featsJson)
        insAffix.executeInsert()
    }

    override fun onCompat(entry: CompatEntry) {
        insCompat.bindString(1, entry.kind)
        insCompat.bindString(2, entry.a)
        insCompat.bindString(3, entry.b)
        insCompat.executeInsert()
    }

    override fun onCounts(counts: Map<String, String>) {
        this.counts = counts
        db.execSQL("CREATE INDEX idx_stems_stem ON stems(stem)")
        db.execSQL("CREATE INDEX idx_affixes_form ON affixes(form, kind)")
        db.execSQL("CREATE INDEX idx_compat_ab ON compat(kind, a)")
        val buildDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        for ((key, value) in CamelMorphologyParser.metaMap(counts, buildDate)) {
            db.execSQL("INSERT INTO meta VALUES (?,?)", arrayOf(key, value))
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        db.execSQL("VACUUM")
        finished = true
    }

    override fun close() {
        insStem.close()
        insAffix.close()
        insCompat.close()
        if (!finished) {
            try { db.endTransaction() } catch (_: Exception) {}
        }
        db.close()
    }

    companion object {
        private val SCHEMA_STATEMENTS = listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
            "CREATE TABLE stems (stem TEXT NOT NULL, cat TEXT NOT NULL, pos TEXT NOT NULL, lex TEXT NOT NULL, lex_logprob REAL NOT NULL, root TEXT, feats TEXT)",
            "CREATE TABLE affixes (form TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('prefix','suffix')), cat TEXT NOT NULL, feats TEXT NOT NULL)",
            "CREATE TABLE compat (kind TEXT NOT NULL CHECK(kind IN ('ab','bc','ac')), a TEXT NOT NULL, b TEXT NOT NULL)",
        )

        /**
         * Строки morphology.db из потока (или из .zip, содержащего его).
         * Block гарантированно вызывается внутри use{}; последовательность не должна покидать block.
         */
        internal fun <T> withLines(
            input: InputStream,
            displayName: String?,
            block: (Sequence<String>) -> T,
        ): T {
            val isZip = displayName?.lowercase(Locale.US)?.endsWith(".zip") == true
            return if (isZip) {
                val zip = ZipInputStream(input.buffered())
                var entry = zip.nextEntry
                var found = false
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.US)
                    if (!entry.isDirectory && name.endsWith("morphology.db")) {
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
                if (!found) {
                    zip.close()
                    throw IllegalArgumentException("в архиве нет morphology.db")
                }
                InputStreamReader(zip, StandardCharsets.UTF_8).buffered().use { reader ->
                    block(reader.lineSequence())
                }
            } else {
                InputStreamReader(input, StandardCharsets.UTF_8).buffered().use { reader ->
                    block(reader.lineSequence())
                }
            }
        }

        /**
         * Полный цикл: morphology.db (или zip c ним) → camel_pack.sqlite.
         * При любой ошибке частичный файл удаляется. Вызывать на Dispatchers.IO.
         */
        fun convert(
            input: InputStream,
            displayName: String?,
            outPath: String,
            onProgress: (Long) -> Unit = {},
        ): CamelBuildResult {
            val out = File(outPath)
            val writer = try {
                AndroidCamelPackWriter(outPath)
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            try {
                val (lines, done) = withLines(input, displayName) { seq ->
                    val n = CamelMorphologyParser.parse(seq, writer, onProgress)
                    n to writer.lastCounts
                }
                writer.close()
                return CamelBuildResult(lines, done, out.length())
            } catch (e: Exception) {
                try { writer.close() } catch (_: Exception) {}
                out.delete()
                throw e
            }
        }
    }

    /** Счётчики, полученные в onCounts (до close()). */
    val lastCounts: Map<String, String> get() = counts
}
