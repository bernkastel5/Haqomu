package com.bernkastel5.haqomu.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Генерация .apkg из накопленных карточек (Req R11). Воспроизводит note type и id
 * из tools/build_haqomu_apkg.py, поэтому импорт сливается с импортированным набором
 * Haqomu (те же model/deck id и тот же guid → Anki дедуплицирует).
 *
 * Словарных данных в файле нет, кроме текста самих карточек (их написал пользователь).
 */
object ApkgPackager {

    /** Собрать .apkg с накопленными карточками в `outFile`. @return outFile. */
    fun buildExport(context: Context, cards: List<PendingCard>, outFile: File): File {
        val dbFile = File(context.cacheDir, "haqomu_export.anki2")
        if (dbFile.exists()) dbFile.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (statement in AnkiModel.SCHEMA_SQL.split(";")) {
                val sql = statement.trim()
                if (sql.isNotEmpty()) db.execSQL(sql)
            }
            val nowSec = System.currentTimeMillis() / 1000
            val baseId = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO col VALUES(1,1763078400,?,?,11,0,0,0,?,?,?,?,'{}')",
                arrayOf(
                    baseId,
                    baseId,
                    AnkiModel.colConfJson(),
                    "{}", // models заполним ниже
                    "{}", // decks заполним ниже
                    AnkiModel.colDconfJson(),
                )
            )
            // models/decks в col как валидный JSON
            db.execSQL(
                "UPDATE col SET models = ?, decks = ?",
                arrayOf(
                    "{\"${AnkiModel.MODEL_ID}\":${AnkiModel.modelJson(nowSec)}}",
                    "{\"${AnkiModel.DECK_ID}\":${AnkiModel.deckJson()}}",
                )
            )

            var index = 0
            for (card in cards) {
                val noteId = baseId + index * 2L
                val cardId = noteId + 1
                val fields = card.toFields()
                val guid = AnkiModel.guidFor(*fields)
                val sortField = fields[0]
                db.execSQL(
                    "INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        noteId,
                        guid,
                        AnkiModel.MODEL_ID,
                        nowSec,
                        -1,
                        " ",
                        AnkiModel.joinFields(fields),
                        sortField,
                        AnkiModel.csumFor(sortField),
                        0,
                        "",
                    )
                )
                db.execSQL(
                    "INSERT INTO cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        cardId,
                        noteId,
                        AnkiModel.DECK_ID,
                        0,
                        nowSec,
                        -1,
                        0,
                        0,
                        (index + 1).toLong(), // due: порядок среди новых
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "",
                    )
                )
                index += 1
            }
        } finally {
            db.close()
        }

        if (outFile.exists()) outFile.delete()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("media"))
            zip.write("{}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        dbFile.delete()
        return outFile
    }

    /**
     * Собрать .apkg в файлы и поделиться им (SAF/Intent делает вызывающий код).
     * Доступен только с карточками в PendingStore; пустой список — не ошибка, просто false.
     */
    fun exportToFile(context: Context, fileName: String = "haqomu-cards.apkg"): File? {
        val cards = PendingStore.load(context)
        if (cards.isEmpty()) return null
        val outFile = File(context.cacheDir, fileName)
        return buildExport(context, cards, outFile)
    }
}
