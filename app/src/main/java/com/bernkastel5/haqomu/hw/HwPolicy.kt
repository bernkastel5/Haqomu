package com.bernkastel5.haqomu.hw

import java.text.Normalizer

/**
 * Чистая логика разбора статей Hans Wehr — порт tools/build_hw_pack.py (секции
 * «утилиты», «разбор статьи», «иерархия корней»). Android-чтение SQLite — в
 * AndroidHansWehrPackBuilder. Тестируется на JVM (HwPolicyTest).
 */
object HwPolicy {

    private const val AR_BLOCKS = "\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF"
    private const val DIACRITICS = "\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u0640"
    private const val LATIN_CHARS =
        "A-Za-z\\u00C0-\\u00FF\\u0100-\\u017F\\u1E00-\\u1EFF\\u02B0-\\u02FF\\u2018-\\u201F'\\u0027-"

    private val DIAC_RE = Regex("[$DIACRITICS]")
    private val AR_ONLY = Regex("^[$AR_BLOCKS$DIACRITICS]+\$")
    private val LATIN_ONLY = Regex("^[$LATIN_CHARS]+\$")
    private val NON_AR_RUN = Regex("[^\\u0600-\\u06FF]+")
    private val TATWEEL = "ـ"
    private val STRAY_NUM = Regex("(?<![\\w\\-\\u0600-\\u06FF])\\d{2,4}(?![\\w\\-])")
    private val GLUED_DIGIT = Regex("\\b([A-Za-z\\u00C0-\\u017F\\u1E00-\\u1EFF]{3,})\\d\\b")
    private val WS = Regex("\\s+")

    private val VERB_FORM_RE = Regex("<b>(I|II|III|IV|V|VI|VII|VIII|IX|X)</b>")
    private val PLURAL_RE = Regex("\\bpl\\.\\s*([^;│]{1,60})")
    private val SEE_RE = Regex(
        "^(?<head>[^\\s]+(?:\\s+[^\\s]+)?)\\s+(?:\\((?<alt>[^)]+)\\)\\s+)?" +
            "(?:(?<rom>[^\\s\\u0600-\\u06FF]+)\\s+)?see\\s+(?<target>[\\u0600-\\u06FF][\\u0600-\\u06FF\\s]*)$"
    )
    private val ALPHA_REF_RE = Regex("look\\s+up\\s+alphabetically", RegexOption.IGNORE_CASE)

    private val ROMAN_STOP = setOf("see", "cf", "pl", "and", "the", "or", "also", "below", "above")

    data class ParsedEntry(
        val headword: String,
        val headwordVocalized: String?,
        val romanization: String?,
        val body: String,
        val idioms: String,
        val verbForms: String,
        val plurals: String,
        val kind: String,           // entry | root-кандидат? нет: entry | xref | xref_alpha | empty
        val seeTarget: String?,
        val altRoot: String?,
    )

    data class RootPlan(
        val roots: Set<Long>,       // id корневых статей
        val rootOf: Map<Long, Long>, // entry id -> root id (корневая статья)
    )

    fun collapseWs(s: String?): String = WS.replace(s ?: "", " ").trim()

    fun stripDiacritics(s: String?): String = DIAC_RE.replace(s ?: "", "")

    fun nfc(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFC)

    /** Ключ для поиска: без огласовок, унифицированные алеф/йа/та-марбута/вав, без татвиля. */
    fun normKey(s: String?): String {
        var out = stripDiacritics(nfc(s))
        out = out.replace(TATWEEL, "")
        out = out.replace(Regex("[أإآٱ]"), "ا")
        out = out.replace(Regex("[ىئ]"), "ي")
        out = out.replace(Regex("[ؤ]"), "و")
        out = out.replace('ة', 'ه')
        return NON_AR_RUN.replace(out, "")
    }

    fun isArabicToken(tok: String): Boolean {
        val core = tok.trim { it in "()[]{}.,;:،؛؟·" }
        return core.isNotEmpty() && AR_ONLY.matches(core)
    }

    fun isLatinToken(tok: String): Boolean {
        val core = tok.trim { it in "()[]{}.,;:·" }
        return core.isNotEmpty() && LATIN_ONLY.matches(core) && core.any { it.isLetter() }
    }

    /** Подпоследовательность: все буквы корня встречаются в слове по порядку. */
    fun containsRootLetters(word: String, root: String): Boolean {
        val w = normKey(word)
        val r = normKey(root)
        if (r.isEmpty() || w.isEmpty()) return false
        var j = 0
        for (ch in w) {
            if (j < r.length && ch == r[j]) j += 1
        }
        return j == r.length
    }

    /** Чистка текста значения: страницы, надстрочные цифры, висячие точки с запятой, хвосты-союзы. */
    fun cleanBody(text: String): String {
        var t = collapseWs(text)
        t = t.replace("―", "—")
        // породы хранятся в поле verb_forms — из тела убираем целиком
        t = VERB_FORM_RE.replace(t, "")
        t = Regex("</?b>").replace(t, "")
        t = Regex("(?<=[A-Za-z])‘(?=[A-Za-z])").replace(t, "’")
        t = STRAY_NUM.replace(t, "")
        t = GLUED_DIGIT.replace(t, "$1")
        t = Regex("\\s*;\\s*;\\s*").replace(t, "; ")
        t = Regex("\\(\\s*\\)").replace(t, "")
        t = collapseWs(t)
        t = t.replace(Regex("^(?:and\\s+|[,;)\\-—·]+\\s*)+"), "")
        return t.trim(' ', ',', ';', ':', '-', '—', '·')
    }

    /** Разбор definition на поля. Kind: entry | xref | xref_alpha | empty. */
    fun parseEntry(word: String, definition: String): ParsedEntry {
        val raw = collapseWs(definition)
        var headword = stripDiacritics(word).trim()
        var headwordVocalized = if (DIAC_RE.containsMatchIn(word)) word.trim() else null
        var romanization: String? = null
        var body = ""
        var idioms = ""
        var verbForms = ""
        var plurals = ""
        var kind = "entry"
        var seeTarget: String? = null
        var altRoot: String? = null

        if (raw.isEmpty()) {
            return ParsedEntry(headword, headwordVocalized, null, "", "", "", "", "empty", null, null)
        }

        // 1) отсылки
        val seeMatch = SEE_RE.find(raw)
        if (seeMatch != null && seeMatch.range.first == 0 &&
            seeMatch.groups["target"]!!.value.trim().split(WS).size <= 3
        ) {
            kind = "xref"
            seeTarget = seeMatch.groups["target"]!!.value.trim()
            romanization = seeMatch.groups["rom"]?.value?.trim()?.ifEmpty { null }
            return ParsedEntry(headword, headwordVocalized, romanization, "", "", "", "", kind, seeTarget, null)
        }
        if (ALPHA_REF_RE.containsMatchIn(raw) && raw.length < 60) {
            return ParsedEntry(headword, headwordVocalized, null, "", "", "", "", "xref_alpha", null, null)
        }

        // 2) отрезаем блок идиом
        val pipe = raw.indexOf('│')
        val bodyMain: String
        if (pipe >= 0) {
            bodyMain = raw.substring(0, pipe)
            idioms = cleanBody(raw.substring(pipe + 1).replace("│", " ; "))
        } else {
            bodyMain = raw
        }

        // 3) заголовок + транслитерация
        val tokens = bodyMain.split(' ').filter { it.isNotEmpty() }
        var i = 0
        val headParts = mutableListOf<String>()
        while (i < tokens.size) {
            val tok = tokens[i]
            if (isArabicToken(tok)) {
                headParts.add(tok)
                i += 1
                continue
            }
            if (tok.startsWith("(") && i + 1 < tokens.size) {
                var j = i
                while (j < tokens.size && !tokens[j].endsWith(")")) j += 1
                if (j < tokens.size) {
                    val inside = tokens.subList(i, j + 1).joinToString(" ").trim('(', ')')
                    if (isArabicToken(inside)) {
                        altRoot = inside
                        i = j + 1
                        continue
                    }
                }
                break
            }
            break
        }

        if (headParts.isNotEmpty()) {
            val headRaw = headParts.joinToString(" ")
            headword = stripDiacritics(headRaw).trim().ifEmpty { headword }
            if (DIAC_RE.containsMatchIn(headRaw)) headwordVocalized = headRaw
        }

        // пропускаем латинские скобки вида (It. abate)
        while (i < tokens.size && tokens[i].startsWith("(")) {
            var j = i
            while (j < tokens.size && !tokens[j].endsWith(")")) j += 1
            i = if (j < tokens.size) j + 1 else tokens.size
        }

        if (i < tokens.size) {
            val t = tokens[i]
            if (isLatinToken(t) && t.lowercase() !in ROMAN_STOP) {
                romanization = t.trim('(', ')', ',', ';', '.')
                i += 1
            }
        }
        val rest = tokens.subList(i, tokens.size).joinToString(" ")

        val canonical = "I II III IV V VI VII VIII IX X".split(' ')
        val forms = VERB_FORM_RE.findAll(bodyMain).map { it.groupValues[1] }.toSet()
            .sortedBy { canonical.indexOf(it) }
        verbForms = forms.joinToString(",")

        plurals = PLURAL_RE.findAll(bodyMain)
            .map { collapseWs(it.groupValues[1]).trim(' ', ',', ';', '.') }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .take(300)

        body = cleanBody(rest)
        return ParsedEntry(headword, headwordVocalized, romanization, body, idioms, verbForms, plurals, kind, seeTarget, altRoot)
    }

    /**
     * Восстановление иерархии «корень → производные»: корни — это строки источника
     * с is_root=1, арабские, без пробела, 2–5 букв (выбор замером, см. build-report.md §8).
     * Каждой некорневой строке достаётся ближайший предшествующий корень.
     */
    fun rebuildRoots(rows: List<RawRow>): RootPlan {
        fun isCandidate(r: RawRow): Boolean {
            if (r.isRootSrc != 1) return false
            val w = r.word.trim()
            val key = normKey(w)
            return " " !in w && key.length in 2..5 && AR_ONLY.matches(stripDiacritics(w))
        }

        val roots = rows.filter(::isCandidate).map { it.id }.sorted().toSet()
        val rootList = roots.sorted()
        val rootOf = HashMap<Long, Long>(rows.size * 2)
        var prev: Long? = null
        var ri = 0
        for (rid in rows.map { it.id }.sorted()) {
            while (ri < rootList.size && rootList[ri] <= rid) {
                prev = rootList[ri]
                ri += 1
            }
            val assigned = if (rid in roots) rid else prev
            if (assigned != null) rootOf[rid] = assigned
        }
        return RootPlan(roots, rootOf)
    }

    data class RawRow(val id: Long, val word: String, val definition: String, val isRootSrc: Int, val quran: String?)
}
