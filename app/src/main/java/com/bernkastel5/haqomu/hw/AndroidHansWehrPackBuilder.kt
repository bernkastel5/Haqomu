package com.bernkastel5.haqomu.hw

import android.database.sqlite.SQLiteDatabase
import com.bernkastel5.haqomu.pack.AndroidPackWriter
import java.io.File

/**
 * Hans Wehr (hanswehr.sqlite, DICTIONARY_content(id, c1=word, c2=definition, c3=is_root,
 * c5=qur'an)) → единый пак §4 (entry/sense/sense_fts). Порт tools/build_hw_pack.py.
 *
 * Отличия от эталона (осознанные, ADR-0006):
 *  - формы слов и индексы forms/entries kinds не переносятся: §4 их не содержит
 *    (поиск идёт по entry.head_norm);
 *  - xref/xref_alpha/empty статьи пропускаются (в §4 нет поля see_entry_id, а глухие
 *    заглушки «انظر» в попапе бесполезны);
 *  - отчёты и спот-чек не пишутся (это исследовательская часть эталона).
 *
 * Вход — путь к скачанному hanswehr.sqlite (мастер качает его во временный файл кэша).
 * Выход — единый пак §4 по [outPath]; источник внутрь пака записывается в meta.
 */
object AndroidHansWehrPackBuilder {

    data class BuildResult(
        val entriesWritten: Long,
        val sensesWritten: Long,
        val skippedXref: Int,
        val skippedEmpty: Int,
        val skippedEmptyBody: Int,
        val roots: Int,
    )

    /**
     * Конвертирует hanswehr.sqlite по пути [srcPath]. [onProgress] получает число прочитанных строк.
     * При исключении частичный выходной файл остаётся на месте — вызывающий код обязан его удалить.
     */
    fun convert(
        srcPath: String,
        outPath: String,
        onProgress: (Long) -> Unit = {},
    ): BuildResult {
        val src = SQLiteDatabase.openDatabase(srcPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val rawRows = src.rawQuery(
                "SELECT id, c1, c2, c3, c5 FROM DICTIONARY_content ORDER BY id",
                null,
            ).use { c ->
                val out = ArrayList<HwPolicy.RawRow>(c.count)
                while (c.moveToNext()) {
                    out.add(
                        HwPolicy.RawRow(
                            id = c.getLong(0),
                            word = if (c.isNull(1)) "" else c.getString(1).trim(),
                            definition = if (c.isNull(2)) "" else c.getString(2),
                            isRootSrc = if (c.isNull(3)) 0 else c.getInt(3),
                            quran = if (c.isNull(4)) null else c.getString(4).trim(),
                        )
                    )
                    onProgress(out.size.toLong())
                }
                out
            }

            val parsed = rawRows.map { row -> row to HwPolicy.parseEntry(row.word, row.definition) }
            val rootPlan = HwPolicy.rebuildRoots(rawRows)
            val wordById = rawRows.associate { it.id to it.word }

            val writer = AndroidPackWriter(outPath)
            var skippedXref = 0
            var skippedEmpty = 0
            var skippedEmptyBody = 0
            var senseId = 0L
            try {
                for ((row, p) in parsed) {
                    when (p.kind) {
                        "xref", "xref_alpha" -> {
                            skippedXref += 1
                            continue
                        }
                        "empty" -> {
                            skippedEmpty += 1
                            continue
                        }
                    }
                    if (p.body.isBlank()) {
                        skippedEmptyBody += 1
                        continue
                    }
                    val headNorm = HwPolicy.normKey(p.headword)
                    if (headNorm.isEmpty()) {
                        skippedEmptyBody += 1
                        continue
                    }
                    // корень производной статьи: ближайший предшествующий корень, показать голым ключом
                    val rootWord = rootPlan.rootOf[row.id]?.let { wordById[it] }
                    val rootKey = rootWord?.let { HwPolicy.normKey(it) }?.ifEmpty { null }
                    val head = p.headwordVocalized?.ifBlank { null } ?: p.headword
                    writer.addEntry(
                        id = row.id,
                        head = head,
                        headNorm = headNorm,
                        root = rootKey,
                        pos = null,
                        source = SOURCE_TAG,
                    )
                    senseId += 1
                    val example = buildString {
                        if (p.idioms.isNotBlank()) append(p.idioms.take(300))
                        if (p.plurals.isNotBlank()) {
                            if (isNotEmpty()) append(" — ")
                            append("pl. ").append(p.plurals)
                        }
                    }.ifBlank { null }
                    writer.addSense(
                        id = senseId,
                        entryId = row.id,
                        ord = 1,
                        definition = p.body.take(1000),
                        glossEn = p.body.take(300),
                        example = example,
                        tags = p.verbForms.ifBlank { null },
                    )
                }
                val total = writer.finish(
                    source = SOURCE_LABEL,
                    license = LICENSE_LABEL,
                    langFrom = "ar",
                    langTo = "en",
                )
                return BuildResult(
                    entriesWritten = total,
                    sensesWritten = writer.sensesWritten(),
                    skippedXref = skippedXref,
                    skippedEmpty = skippedEmpty,
                    skippedEmptyBody = skippedEmptyBody,
                    roots = rootPlan.roots.size,
                )
            } finally {
                writer.close()
            }
        } finally {
            src.close()
        }
    }

    const val SOURCE_TAG = "hanswehr"
    const val SOURCE_LABEL =
        "Hans Wehr, A Dictionary of Modern Written Arabic, 4th ed. (ed. J. M. Cowan, 1979)"
    const val LICENSE_LABEL =
        "Текст словаря охраняется авторским правом. Пак для личного использования; распространение запрещено."

    /** Удаляет исходник и частичный выход при неудаче (вызов из мастера). */
    fun discard(tmpFiles: List<File>) {
        for (f in tmpFiles) runCatching { f.delete() }
    }
}
