package com.bernkastel5.haqomu.anki

import java.security.MessageDigest

/**
 * Набор Haqomu для Anki (D-009): колода «Haqomu» и note type «Haqomu Arabic»
 * (адаптация JP Mining Note, фуригана → ташкиль). Фиксированные id, чтобы повторный
 * импорт .apkg обновлял, а не дублировал колоду и тип в коллекции пользователя.
 *
 * Идентификаторы и имена обязаны совпадать с tools/build_haqomu_apkg.py.
 * Файл haqomu.apkg, лежащий в res/raw, собран этим скриптом; экспорт накопленных
 * карточек (ApkgPackager) воспроизводит тот же note type и тот же guid-алгоритм,
 * поэтому обе версии сливаются в коллекции Anki.
 */
object AnkiModel {
    const val MODEL_ID: Long = 1763078400000L
    const val DECK_ID: Long = 1763078400001L
    const val MODEL_NAME: String = "Haqomu Arabic"
    const val DECK_NAME: String = "Haqomu"

    const val EF_EXPRESSION = "Expression"
    const val EF_TASHKEEL = "Tashkeel"
    const val EF_MEANING = "Meaning"
    const val EF_SENTENCE = "Sentence"
    const val EF_ROOT = "Root"
    const val EF_DICTIONARY = "Dictionary"
    const val EF_NOTES = "Notes"

    val FIELDS: List<String> = listOf(
        EF_EXPRESSION, EF_TASHKEEL, EF_MEANING, EF_SENTENCE, EF_ROOT, EF_DICTIONARY, EF_NOTES,
    )

    private val FIELD_RTL = mapOf(
        EF_EXPRESSION to true, EF_TASHKEEL to true, EF_MEANING to true,
        EF_SENTENCE to true, EF_ROOT to true, EF_DICTIONARY to false, EF_NOTES to false,
    )

    /** Разделитель полей в notes.flds (единый для Anki и нашего .apkg). */
    const val FIELD_SEPARATOR = '\u001f'

    fun joinFields(fields: Array<String>): String =
        fields.joinToString(FIELD_SEPARATOR.toString())

    /** Порядок полей: Expression, Tashkeel, Meaning, Sentence, Root, Dictionary, Notes. */
    fun fieldsArray(
        expression: String,
        tashkeel: String,
        meaning: String,
        sentence: String,
        root: String,
        dictionary: String,
        notes: String,
    ): Array<String> = arrayOf(expression, tashkeel, meaning, sentence, root, dictionary, notes)

    private const val BASE91 =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
            "!#\$%&()*+,-./:;<=>?@[]^_`{|}~"

    /**
     * guid как в Anki: base91(sha256(join("__", fields))[:8]).
     * Стабилен для одинакового набора полей — Anki дедуплицирует по guid при импорте.
     */
    fun guidFor(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("__").toByteArray(Charsets.UTF_8))
        var n = java.math.BigInteger(1, digest.copyOfRange(0, 8))
        val base = java.math.BigInteger.valueOf(BASE91.length.toLong())
        val out = StringBuilder()
        while (n.signum() > 0) {
            val pair = n.divideAndRemainder(base)
            out.append(BASE91[pair[1].toInt()])
            n = pair[0]
        }
        return out.reverse().toString()
    }

    /** csum поля сортировки (sfld), как в Anki: первые 4 байта sha1 как unsigned hex. */
    fun csumFor(sortField: String): Long =
        MessageDigest.getInstance("SHA-1")
            .digest(sortField.toByteArray(Charsets.UTF_8))
            .take(4)
            .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xffL) }

    fun modelJson(timestampSec: Long): String {
        val fields = FIELDS.mapIndexed { index, name ->
            """{"name":${Json.quoted(name)},"ord":$index,"sticky":false,"rtl":${FIELD_RTL[name]},""" +
                """"font":${Json.quoted(if (FIELD_RTL[name] == true) "Amiri" else "Liberation Sans")},"size":20,""" +
                """"media":[],"collapsed":false,"description":""}"""
        }.joinToString(",")
        return """{"id":"$MODEL_ID","name":${Json.quoted(MODEL_NAME)},"type":0,"mod":$timestampSec,"usn":-1,""" +
            """"sortf":0,"did":$DECK_ID,"tmpls":[{"name":"Card 1","ord":0,"qfmt":${Json.quoted(QFMT)},"afmt":${Json.quoted(AFMT)},""" +
            """"bqfmt":"","bafmt":"","bfont":"","bsize":0,"did":null}],"flds":[$fields],""" +
            """"css":${Json.quoted(CSS)},"latexPre":${Json.quoted(LATEX_PRE)},"latexPost":${Json.quoted(LATEX_POST)},""" +
            """"latexsvg":false,"req":[[0,"all",[1]]],"tags":[],"vers":[]}"""
    }

    fun deckJson(): String =
        """{"collapsed":false,"conf":1,"desc":"","dyn":0,"extendNew":10,"extendRev":50,"id":$DECK_ID,""" +
            """"lrnToday":[0,0],"mod":1763078400,"name":${Json.quoted(DECK_NAME)},"newToday":[0,0],""" +
            """"revToday":[0,0],"timeToday":[0,0],"usn":-1}"""

    fun colConfJson(): String =
        """{"activeDecks":[$DECK_ID],"addToCur":true,"collapseTime":1200,"curDeck":$DECK_ID,""" +
            """"curModel":"$MODEL_ID","dueCounts":true,"estTimes":true,"newBury":true,"newSpread":0,""" +
            """"nextPos":1,"sortBackwards":false,"sortType":"noteFld","timeLim":0}"""

    fun colDconfJson(): String =
        """{"1":{"autoplay":true,"id":1,"lapse":{"delays":[10],"leechAction":0,"leechFails":8,"minInt":1,"mult":0},""" +
            """"maxTaken":60,"mod":0,"name":"Default","new":{"bury":true,"delays":[1,10],""" +
            """"initialFactor":2500,"ints":[1,4,7],"order":1,"perDay":20,"separate":true},"replayq":true,""" +
            """"rev":{"bury":true,"ease4":1.3,"fuzz":0.05,"ivlFct":1,"maxIvl":36500,"minSpace":1,"perDay":100},""" +
            """"timer":0,"usn":0}}"""

    const val QFMT: String =
        "<div class=\"front\">" +
            "<div class=\"word\">{{Tashkeel}}</div>" +
            "{{#Root}}<div class=\"root\">{{Root}}</div>{{/Root}}" +
            "</div>"

    const val AFMT: String =
        "{{FrontSide}}" +
            "<hr id=\"answer\">" +
            "<div class=\"meaning\">{{Meaning}}</div>" +
            "{{#Sentence}}<div class=\"sentence\">{{Sentence}}</div>{{/Sentence}}" +
            "<div class=\"dict\">{{Dictionary}}</div>" +
            "{{#Notes}}<div class=\"notes\">{{Notes}}</div>{{/Notes}}"

    const val CSS: String =
        ".card {\n" +
            "  direction: rtl;\n" +
            "  font-family: 'Amiri', 'Scheherazade New', 'Noto Naskh Arabic', serif;\n" +
            "  font-size: 20px;\n" +
            "  line-height: 1.9;\n" +
            "  color: #1f1b16;\n" +
            "  background: #fdfcf9;\n" +
            "  text-align: center;\n" +
            "  padding: 16px;\n" +
            "}\n" +
            ".word {\n" +
            "  font-size: 40px;\n" +
            "  line-height: 2.1;\n" +
            "}\n" +
            ".root {\n" +
            "  color: #6b655c;\n" +
            "  font-size: 18px;\n" +
            "  margin-top: 4px;\n" +
            "}\n" +
            "hr#answer {\n" +
            "  border: none;\n" +
            "  border-top: 1px solid #d8d4cc;\n" +
            "  margin: 14px 0;\n" +
            "}\n" +
            ".meaning {\n" +
            "  text-align: right;\n" +
            "  line-height: 1.9;\n" +
            "}\n" +
            ".sentence {\n" +
            "  text-align: right;\n" +
            "  color: #3c3831;\n" +
            "  margin-top: 12px;\n" +
            "}\n" +
            ".dict {\n" +
            "  color: #6b655c;\n" +
            "  font-size: 15px;\n" +
            "  text-align: center;\n" +
            "  margin-top: 14px;\n" +
            "}\n" +
            ".notes {\n" +
            "  color: #6b655c;\n" +
            "  font-size: 15px;\n" +
            "  text-align: right;\n" +
            "  margin-top: 10px;\n" +
            "}\n" +
            ".card.nightMode {\n" +
            "  color: #e6e2d8;\n" +
            "  background: #1f1b16;\n" +
            "}\n" +
            ".card.nightMode .root, .card.nightMode .dict, .card.nightMode .notes { color: #a9a499; }\n" +
            ".card.nightMode .sentence { color: #cfccc0; }\n" +
            ".card.nightMode hr#answer { border-top-color: #45413a; }\n"

    const val LATEX_PRE: String =
        "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n" +
            "\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n" +
            "\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n"

    const val LATEX_POST: String = "\\end{document}"

    const val SCHEMA_SQL: String = """
CREATE TABLE col (
    id              integer primary key,
    crt             integer not null,
    mod             integer not null,
    scm             integer not null,
    ver             integer not null,
    dty             integer not null,
    usn             integer not null,
    ls              integer not null,
    conf            text not null,
    models          text not null,
    decks           text not null,
    dconf           text not null,
    tags            text not null
);
CREATE TABLE notes (
    id              integer primary key,
    guid            text not null,
    mid             integer not null,
    mod             integer not null,
    usn             integer not null,
    tags            text not null,
    flds            text not null,
    sfld            integer not null,
    csum            integer not null,
    flags           integer not null,
    data            text not null
);
CREATE TABLE cards (
    id              integer primary key,
    nid             integer not null,
    did             integer not null,
    ord             integer not null,
    mod             integer not null,
    usn             integer not null,
    type            integer not null,
    queue           integer not null,
    due             integer not null,
    ivl             integer not null,
    factor          integer not null,
    reps            integer not null,
    lapses          integer not null,
    left            integer not null,
    odue            integer not null,
    odid            integer not null,
    flags           integer not null,
    data            text not null
);
CREATE TABLE revlog (
    id              integer primary key,
    cid             integer not null,
    usn             integer not null,
    ease            integer not null,
    ivl             integer not null,
    lastIvl         integer not null,
    factor          integer not null,
    time            integer not null,
    type            integer not null
);
CREATE TABLE graves (
    usn             integer not null,
    oid             integer not null,
    type            integer not null
);
CREATE INDEX ix_notes_usn on notes (usn);
CREATE INDEX ix_cards_usn on cards (usn);
CREATE INDEX ix_revlog_usn on revlog (usn);
CREATE INDEX ix_cards_nid on cards (nid);
CREATE INDEX ix_cards_sched on cards (did, queue, due);
CREATE INDEX ix_revlog_cid on revlog (cid);
CREATE INDEX ix_notes_csum on notes (csum);
"""
}
