package com.bernkastel5.haqomu.pack

import android.database.sqlite.SQLiteDatabase
import com.bernkastel5.haqomu.pipeline.DictionaryEntry
import com.bernkastel5.haqomu.pipeline.DictionarySense
import com.bernkastel5.haqomu.pipeline.DictionarySource
import com.bernkastel5.haqomu.pipeline.Families
import java.io.Closeable
import java.io.File

/**
 * Словарь поверх установленных §4-паков (filesDir/packs — все файлы с расширением .sqlite).
 *
 * Семейство определяется по meta('source') и по имени файла: Kaikki → kaikki,
 * Hans Wehr → wehr, KSAA → ksaa; морфологический пак calima пропускается (это не
 * словарь); нераспознанные паки читаются как family="other" (приоритет ниже wehr,
 * см. Decide). Битый или не-§4 файл пропускается и попадает в [skipped] с причиной —
 * установку и проверку таких паков делает мастер/экран импорта, здесь только чтение.
 *
 * Открытых соединений держится по одному на пак; закрытие — [close].
 */
class AndroidPackDictionary private constructor(
    private val packs: List<OpenPack>,
    val skipped: Map<String, String>,
) : DictionarySource, Closeable {

    private class OpenPack(val db: SQLiteDatabase, val family: String, val fileName: String)

    override fun findByHeadNorm(queryNorm: String, limitPerSource: Int): List<DictionaryEntry> {
        if (queryNorm.isEmpty()) return emptyList()
        val out = mutableListOf<DictionaryEntry>()
        // Порядок обхода стабилен: семейства по приоритету (ksaa, kaikki, wehr, other),
        // внутри — порядок имён файлов.
        for (pack in packs) {
            out += queryPack(pack, queryNorm, limitPerSource)
        }
        return out
    }

    private fun queryPack(pack: OpenPack, headNorm: String, limit: Int): List<DictionaryEntry> {
        val entries = mutableListOf<Pair<Long, DictionaryEntry>>()
        pack.db.rawQuery(
            "SELECT id, head, head_norm, root, pos FROM entry WHERE head_norm = ? ORDER BY id LIMIT ?",
            arrayOf(headNorm, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                entries += c.getLong(0) to DictionaryEntry(
                    head = c.getString(1),
                    headNorm = c.getString(2),
                    root = c.getString(3),
                    pos = c.getString(4),
                    sourceFamily = pack.family,
                    senses = emptyList(),
                )
            }
        }
        return entries.map { (id, e) -> e.copy(senses = sensesOf(pack.db, id)) }
    }

    private fun sensesOf(db: SQLiteDatabase, entryId: Long): List<DictionarySense> {
        val out = mutableListOf<DictionarySense>()
        db.rawQuery(
            "SELECT definition, gloss_en FROM sense WHERE entry_id = ? ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += DictionarySense(
                    definition = c.getString(0),
                    glossEn = c.getString(1),
                )
            }
        }
        return out
    }

    override fun close() {
        for (pack in packs) {
            try {
                pack.db.close()
            } catch (_: Exception) {
                // уже закрыт — не беда
            }
        }
    }

    companion object {
        /** Открыть все §4-паки в каталоге. Пустой каталог — пустой словарь (попап покажет «нет статей»). */
        fun open(packsDir: File): AndroidPackDictionary {
            val files = packsDir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }
                ?.sortedBy { it.name }
                ?: return AndroidPackDictionary(emptyList(), emptyMap())
            val packs = mutableListOf<OpenPack>()
            val skipped = LinkedHashMap<String, String>()
            for (file in files) {
                val opened = openOne(file)
                if (opened == null) {
                    skipped[file.name] = "не читается как §4-пак"
                    continue
                }
                val (family, db) = opened
                if (family == null) {
                    skipped[file.name] = "морфологическая база, не словарь"
                    db.close()
                    continue
                }
                packs += OpenPack(db, family, file.name)
            }
            packs.sortWith(compareBy({ Families.priority(it.family) }, { it.fileName }))
            return AndroidPackDictionary(packs, skipped)
        }

        /** Семейство по meta('source') и имени файла; null — пак не является словарём (calima). */
        internal fun detectFamily(sourceMeta: String?, fileName: String): String {
            val hay = ((sourceMeta ?: "") + " " + fileName).lowercase()
            if ("calima" in hay) return "__skip__"
            if ("kaikki" in hay || "wiktionary" in hay) return Families.KAIKKI
            if ("hans wehr" in hay || "hanswehr" in hay || "wehr" in hay) return Families.WEHR
            if ("ksaa" in hay || "siwar" in hay) return Families.KSAA
            return Families.OTHER
        }

        private fun openOne(file: File): Pair<String?, SQLiteDatabase>? {
            return try {
                val db = SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                val tables = mutableSetOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { c ->
                    while (c.moveToNext()) tables += c.getString(0)
                }
                if ("entry" !in tables || "sense" !in tables || "meta" !in tables) {
                    db.close()
                    return null
                }
                val source = db.rawQuery("SELECT value FROM meta WHERE key = 'source'", emptyArray())
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val detected = detectFamily(source, file.name)
                if (detected == "__skip__") return null to db
                detected to db
            } catch (_: Exception) {
                null
            }
        }
    }
}
