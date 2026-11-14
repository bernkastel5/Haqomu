package com.bernkastel5.haqomu.kaikki

import com.bernkastel5.haqomu.pack.AndroidPackWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Kaikki (Wiktionary ar→en, JSONL[.gz]) → единый пак §4 (entry/sense/sense_fts).
 * Порт tools/build_kaikki_index.py: нормализация и отбор смыслов — KaikkiPolicy
 * (pick_gloss-семантика); словаформы (forms) и form_of-указатели в §4 не кладём
 * (таблицы нет); см. ADR-0006.
 *
 * Вход — поток на скачанный kaikki.org-dictionary-Arabic.jsonl[.gz] (определение по [fileName]).
 * Выход — единый пак §4 по [outPath]. При исключении частичный файл остаётся — вызывающий удаляет.
 */
object AndroidKaikkiPackBuilder {

    data class BuildResult(
        val entriesWritten: Long,
        val sensesWritten: Long,
        val skippedNotArabic: Int,
        val skippedNoSense: Int,
        val duplicatesSkipped: Int,
    )

    fun convert(
        input: InputStream,
        fileName: String,
        outPath: String,
        onProgress: (Long) -> Unit = {},
    ): BuildResult {
        val buffered = if (fileName.endsWith(".gz", ignoreCase = true)) {
            BufferedReader(InputStreamReader(GZIPInputStream(input), StandardCharsets.UTF_8))
        } else {
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        }

        val writer = AndroidPackWriter(outPath)
        val seen = HashSet<String>()
        var entryId = 0L
        var senseId = 0L
        var skippedNotArabic = 0
        var skippedNoSense = 0
        var duplicatesSkipped = 0
        var lines = 0L
        try {
            buffered.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines += 1
                    if (lines % 500L == 0L) onProgress(lines)
                    if (line.isBlank()) continue
                    val d = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    val word = d.optString("word", "")
                    if (!KaikkiPolicy.isArabicWord(word)) {
                        skippedNotArabic += 1
                        continue
                    }
                    val pos = d.optString("pos", "").ifBlank { null }
                    val sensesJson = d.optJSONArray("senses") ?: JSONArray()
                    val rawSenses = ArrayList<KaikkiPolicy.RawSense>(sensesJson.length())
                    val categoryNames = ArrayList<String>()
                    for (i in 0 until sensesJson.length()) {
                        val s = sensesJson.optJSONObject(i) ?: continue
                        val glosses = s.optJSONArray("glosses")
                        val gloss = if (glosses != null && glosses.length() > 0) {
                            glosses.optString(0, "").trim()
                        } else ""
                        val tagsArr = s.optJSONArray("tags")
                        val tags = ArrayList<String>()
                        if (tagsArr != null) for (t in 0 until tagsArr.length()) tags.add(tagsArr.optString(t))
                        val example = firstExample(s)
                        rawSenses.add(KaikkiPolicy.RawSense(gloss, tags, example))
                        val cats = s.optJSONArray("categories")
                        if (cats != null) {
                            for (c in 0 until cats.length()) {
                                val name = cats.optJSONObject(c)?.optString("name") ?: continue
                                categoryNames.add(name)
                            }
                        }
                    }
                    val plan = KaikkiPolicy.planSenses(rawSenses)
                    if (plan == null) {
                        skippedNoSense += 1
                        continue
                    }
                    val headExact = KaikkiPolicy.stripDiac(word)
                    val headNorm = KaikkiPolicy.norm(word)
                    if (headNorm.isEmpty()) {
                        skippedNotArabic += 1
                        continue
                    }
                    val sig = buildString {
                        append(headNorm).append('|').append(pos ?: "")
                        for (sense in plan) append('|').append(sense.definition)
                    }
                    if (!seen.add(sig)) {
                        duplicatesSkipped += 1
                        continue
                    }
                    val root = KaikkiPolicy.rootFromCategories(categoryNames)
                    entryId += 1
                    writer.addEntry(
                        id = entryId,
                        head = headExact,
                        headNorm = headNorm,
                        root = root,
                        pos = pos,
                        source = SOURCE_TAG,
                    )
                    for (sense in plan) {
                        senseId += 1
                        writer.addSense(
                            id = senseId,
                            entryId = entryId,
                            ord = sense.ord,
                            definition = sense.definition,
                            glossEn = sense.glossEn,
                            example = sense.example,
                            tags = sense.tags.joinToString(",").ifBlank { null },
                        )
                    }
                }
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
                skippedNotArabic = skippedNotArabic,
                skippedNoSense = skippedNoSense,
                duplicatesSkipped = duplicatesSkipped,
            )
        } finally {
            writer.close()
        }
    }

    private fun firstExample(sense: JSONObject): String? {
        val examples = sense.optJSONArray("examples") ?: return null
        for (i in 0 until examples.length()) {
            val e = examples.optJSONObject(i) ?: continue
            val text = e.optString("text", "").trim()
            if (text.isNotEmpty()) return text.take(300)
        }
        return null
    }

    const val SOURCE_TAG = "kaikki"
    const val SOURCE_LABEL = "Kaikki.org — Wiktionary, Arabic entries (CC BY-SA 4.0)"
    const val LICENSE_LABEL = "CC BY-SA 4.0 — данные Wiktionary через kaikki.org; требуется атрибуция и ShareAlike при распространении."
}
