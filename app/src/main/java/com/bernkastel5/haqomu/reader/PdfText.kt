package com.bernkastel5.haqomu.reader

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Текст из PDF без сторонних библиотек (ADR-0011).
 *
 * Зачем свой разбор. Для PDF **с текстовым слоем** нужен только текст: показать
 * страницу картинкой здесь нечем (нет рендера), а `android.graphics.pdf.PdfRenderer`
 * умеет рисовать и не умеет отдавать текст. PDFBox-Android заброшен и весит больше,
 * чем всё приложение; тянуть его ради текста — плохая сделка.
 *
 * Что делается (ровно то, что встречается в книгах, и ничего сверх):
 *  1. Сканируются объекты `N G obj … endobj`; поток `stream … endstream` распаковывается
 *     `Inflater` при `FlateDecode` (остальные кодировки пропускаются как есть).
 *  2. Если объекты лежат в сжатых потоках (`/ObjStm`), их содержимое разбирается тоже —
 *     так сделано почти во всех современных книгах.
 *  3. Контент страниц берётся из `/Contents` страниц в порядке `/Pages` → `/Kids`.
 *  4. Из контента читаются операторы показа текста `Tj`, `TJ`, `'`, `"`; строки
 *     декодируются по `/ToUnicode` CMap шрифта (`bfchar`/`bfrange`), при его отсутствии —
 *     как WinAnsi/Latin-1. Шрифт без CMap с двухбайтовыми кодами (Identity-H) честно
 *     пропускается: показать «крякозябры» хуже, чем не показать ничего.
 *
 * Ограничения честные и осознанные: нет OCR (сканы без текстового слоя — отдельная
 * задача), нет шифрования (книга с паролем не откроется), нет колонок и таблиц
 * (порядок — как в контент-потоке, а не как в вёрстке), нет шрифтовых ligature-разбора
 * за пределами CMap. Всё это — поводы для отдельного решения, а не скрытые сюрпризы.
 */
object PdfText {

    /** Сколько распакованного текста максимум берём: защита от «бомб» в zip. */
    private const val MAX_STREAM_BYTES = 8 * 1024 * 1024

    /** Сколько страниц максимум разбираем: книга на 2000 страниц не нужна целиком. */
    const val MAX_PAGES = 1200

    /** Итог: страницы текста по порядку. */
    fun extract(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val doc = PdfDocument(bytes)
        return try {
            doc.pageTexts()
        } catch (_: Exception) {
            emptyList()
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------------ документ

    /** Разобранные объекты PDF: номер → сырое тело (словарь + поток). */
    private class PdfDocument(private val bytes: ByteArray) {
        private val objects = HashMap<Int, String>()
        /** Распакованные потоки по номеру объекта. */
        private val streams = HashMap<Int, ByteArray>()

        fun close() {
            objects.clear()
            streams.clear()
        }

        fun pageTexts(): List<String> {
            parseObjects()
            parseObjectStreams()
            val pages = pageObjects()
            val texts = mutableListOf<String>()
            for (page in pages) {
                if (texts.size >= MAX_PAGES) break
                val text = textOfPage(page)
                if (text.isNotBlank()) texts += text
            }
            return texts
        }

        // -------------------------------------------------------------- объекты

        private fun parseObjects() {
            val text = String(bytes, Charsets.ISO_8859_1)
            var i = 0
            while (i < text.length) {
                val objAt = text.indexOf(" obj", i)
                if (objAt < 0) break
                // перед " obj" стоит номер объекта, перед ним — поколение
                val headerStart = text.lastIndexOf('\n', objAt).let { if (it < 0) 0 else it + 1 }
                val header = text.substring(headerStart, objAt).trim()
                val num = header.substringBefore(' ').toIntOrNull()
                val bodyStart = objAt + 4
                val endObj = text.indexOf("endobj", bodyStart)
                val body = if (endObj < 0) text.substring(bodyStart) else text.substring(bodyStart, endObj)
                if (num != null) {
                    objects[num] = body
                    val streamStart = body.indexOf("stream", 0, ignoreCase = true)
                    if (streamStart >= 0) {
                        val dataStart = bodyStart + streamStart + "stream".length +
                            if (body.getOrNull(streamStart + 6) == '\r' && body.getOrNull(streamStart + 7) == '\n') 2
                            else if (body.getOrNull(streamStart + 6) == '\n' || body.getOrNull(streamStart + 6) == '\r') 1
                            else 0
                        val streamEnd = body.indexOf("endstream", streamStart, ignoreCase = true)
                        if (streamEnd > 0) {
                            // конец данных: перед "endstream" обычно перевод строки — он не текст потока
                            var dataEndGlobal = bodyStart + streamEnd
                            while (dataEndGlobal > dataStart && (bytes[dataEndGlobal - 1] == '\n'.code.toByte() ||
                                    bytes[dataEndGlobal - 1] == '\r'.code.toByte())
                            ) {
                                dataEndGlobal--
                            }
                            if (dataStart in 0..dataEndGlobal) {
                                val raw = copyRange(dataStart, dataEndGlobal)
                                val unpacked = inflateIfNeeded(body.substring(0, streamStart), raw)
                                if (unpacked != null) streams[num] = unpacked
                            }
                        }
                    }
                }
                i = if (endObj < 0) text.length else endObj + 6
            }
        }

        private fun copyRange(from: Int, to: Int): ByteArray {
            if (from < 0 || to > bytes.size || from >= to) return ByteArray(0)
            return bytes.copyOfRange(from, to)
        }

        /**
         * Распаковка потока: FlateDecode через [Inflater]. null — поток не текстовый
         * (картинка) или сжат так, что читать без библиотеки нет смысла.
         */
        private fun inflateIfNeeded(dict: String, raw: ByteArray): ByteArray? {
            val flate = dict.contains("FlateDecode")
            if (!flate) {
                return if (dict.contains("/Image") || dict.contains("/DCTDecode") || dict.contains("/JPXDecode")) null else raw
            }
            return try {
                val inflater = Inflater()
                inflater.setInput(raw)
                val out = java.io.ByteArrayOutputStream(minOf(raw.size * 4, 1 shl 20))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (!inflater.finished() && total < MAX_STREAM_BYTES) {
                    val n = try {
                        inflater.inflate(buffer)
                    } catch (_: DataFormatException) {
                        break
                    }
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        continue
                    }
                    out.write(buffer, 0, n)
                    total += n
                }
                inflater.end()
                out.toByteArray()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Объекты из сжатых потоков `/Type /ObjStm`: в словаре есть `/N` (сколько пар)
         * и `/First` (смещение до тел). Без этого современная книга выглядит пустой.
         */
        private fun parseObjectStreams() {
            val snapshot = objects.entries.map { it.key to it.value }
            for ((num, body) in snapshot) {
                if (!body.contains("/ObjStm")) continue
                val data = streams[num] ?: continue
                val n = Regex("/N\\s+(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val first = Regex("/First\\s+(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val header = String(data.copyOfRange(0, minOf(first, data.size)), Charsets.ISO_8859_1)
                val numbers = Regex("\\d+").findAll(header).map { it.value.toInt() }.toList()
                val bodies = String(data.copyOfRange(minOf(first, data.size), data.size), Charsets.ISO_8859_1)
                for (k in 0 until minOf(n, numbers.size / 2)) {
                    val objNum = numbers[k * 2]
                    val offset = numbers[k * 2 + 1]
                    val next = if (k + 1 < numbers.size / 2) numbers[(k + 1) * 2 + 1] else bodies.length
                    if (offset < 0 || offset >= bodies.length) continue
                    val end = minOf(if (next > offset) next else bodies.length, bodies.length)
                    val objBody = bodies.substring(offset, end)
                    objects.putIfAbsent(objNum, objBody)
                }
            }
        }

        // -------------------------------------------------------------- страницы

        /** Страницы в порядке чтения: обход дерева /Pages → /Kids. */
        private fun pageObjects(): List<String> {
            val catalog = objects.entries.firstOrNull { it.value.contains("/Type") && it.value.contains("/Catalog") }?.value
                ?: return objects.values.filter { it.contains("/Type") && it.contains("/Page") && !it.contains("/Pages") }
            val rootRef = Regex("/Pages\\s+(\\d+)\\s+\\d+\\s+R").find(catalog)?.groupValues?.get(1)?.toIntOrNull()
                ?: return emptyList()
            val out = mutableListOf<String>()
            val visited = HashSet<Int>()
            fun walk(num: Int) {
                if (out.size >= MAX_PAGES || !visited.add(num)) return
                val body = objects[num] ?: return
                if (body.contains("/Type") && body.contains("/Pages")) {
                    for (m in Regex("/Kids\\s*\\[([^\\]]*)\\]").findAll(body)) {
                        for (ref in Regex("(\\d+)\\s+\\d+\\s+R").findAll(m.groupValues[1])) {
                            ref.groupValues[1].toIntOrNull()?.let { walk(it) }
                        }
                    }
                } else if (body.contains("/Type") && body.contains("/Page") && !body.contains("/Pages")) {
                    out += body
                }
            }
            walk(rootRef)
            return out
        }

        /** Текст страницы: контент-потоки в порядке /Contents. */
        private fun textOfPage(page: String): String {
            val contents = mutableListOf<ByteArray>()
            val single = Regex("/Contents\\s+(\\d+)\\s+\\d+\\s+R").find(page)
            if (single != null) {
                single.groupValues[1].toIntOrNull()?.let { streams[it]?.let(contents::add) }
            } else {
                val array = Regex("/Contents\\s*\\[([^\\]]*)\\]").find(page)
                if (array != null) {
                    for (ref in Regex("(\\d+)\\s+\\d+\\s+R").findAll(array.groupValues[1])) {
                        ref.groupValues[1].toIntOrNull()?.let { streams[it]?.let(contents::add) }
                    }
                }
            }
            if (contents.isEmpty()) return ""
            val resources = resourcesOf(page)
            return contents.joinToString("\n") { stream ->
                val content = String(stream, Charsets.ISO_8859_1)
                try {
                    PdfContentParser(content) { name -> resources.font(name).info() }.parse()
                } catch (_: Exception) {
                    // один битый поток не должен стоить всей страницы
                    ""
                }
            }
        }

        /**
         * Ресурсы страницы: словарь /Font с ToUnicode. Наследуются от /Parent, поэтому
         * поднимаемся по цепочке, пока не найдём /Font.
         */
        private fun resourcesOf(page: String): PdfResources {
            var body = page
            var depth = 0
            while (depth < 8) {
                val inline = Regex("/Resources\\s*<<").containsMatchIn(body)
                if (inline) return resourcesFromDictionary(body)
                val ref = Regex("/Resources\\s+(\\d+)\\s+\\d+\\s+R").find(body)
                if (ref != null) {
                    val resBody = objects[ref.groupValues[1].toIntOrNull() ?: -1] ?: break
                    return resourcesFromDictionary(resBody)
                }
                val parent = Regex("/Parent\\s+(\\d+)\\s+\\d+\\s+R").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: break
                body = objects[parent] ?: break
                depth++
            }
            return PdfResources.EMPTY
        }

        private fun resourcesFromDictionary(dict: String): PdfResources {
            val fonts = HashMap<String, PdfFont>()
            // /Font << /F1 12 0 R /F2 13 0 R >>
            val fontDict = Regex("/Font\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL).find(dict)?.groupValues?.get(1)
            var fontBody = fontDict
            if (fontBody == null) {
                val fontRef = Regex("/Font\\s+(\\d+)\\s+\\d+\\s+R").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                if (fontRef != null) fontBody = objects[fontRef]
            }
            if (fontBody != null) {
                for (m in Regex("/([A-Za-z0-9_.+-]+)\\s+(\\d+)\\s+\\d+\\s+R").findAll(fontBody)) {
                    val name = m.groupValues[1]
                    val num = m.groupValues[2].toIntOrNull() ?: continue
                    fonts[name] = fontOf(num)
                }
            }
            return PdfResources(fonts)
        }

        private fun fontOf(num: Int): PdfFont {
            val body = objects[num] ?: return PdfFont.UNKNOWN
            val composite = body.contains("Identity-H") || body.contains("/Type0")
            val toUnicodeRef = Regex("/ToUnicode\\s+(\\d+)\\s+\\d+\\s+R").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val cmap = toUnicodeRef?.let { streams[it] }?.let { PdfCMap.parse(it) }
            return PdfFont(composite = composite, cmap = cmap)
        }
    }

    // ------------------------------------------------------------------ ресурсы

    /** Шрифт: двухбайтовый ли код и CMap для перевода кодов в Unicode. */
    private class PdfFont(val composite: Boolean, val cmap: PdfCMap?) {
        fun info(): PdfFontInfo = PdfFontInfo(composite, cmap)

        companion object {
            val UNKNOWN = PdfFont(composite = false, cmap = null)
        }
    }

    private class PdfResources(val fonts: Map<String, PdfFont>) {
        fun font(name: String?): PdfFont =
            if (name == null) PdfFont.UNKNOWN else fonts[name] ?: PdfFont.UNKNOWN

        companion object {
            val EMPTY = PdfResources(emptyMap())
        }
    }

    // ------------------------------------------------------------------ CMap

    /**
     * ToUnicode CMap: `beginbfchar`/`endbfchar` и `beginbfrange`/`endbfrange`.
     * Ключ — код глифа как есть (байты в hex), значение — Unicode.
     */
    class PdfCMap private constructor(private val map: Map<Int, String>) {

        fun text(code: Int): String? = map[code]

        companion object {
            val EMPTY = PdfCMap(emptyMap())

            fun parse(data: ByteArray): PdfCMap {
                val text = String(data, Charsets.ISO_8859_1)
                val out = HashMap<Int, String>()
                for (block in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    for (pair in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1])) {
                        val code = pair.groupValues[1].toIntOrNull(16) ?: continue
                        out[code] = hexToText(pair.groupValues[2])
                    }
                }
                for (block in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    for (m in RANGE.findAll(block.groupValues[1])) {
                        val from = m.groupValues[1].toIntOrNull(16) ?: continue
                        val to = m.groupValues[2].toIntOrNull(16) ?: continue
                        if (to < from || to - from > 0xFFFF) continue
                        val target = m.groupValues[3]
                        if (target.startsWith("<")) {
                            val base = hexToText(target.trim('<', '>'))
                            if (base.isEmpty()) continue
                            val baseCode = base[0].code
                            for (k in 0..(to - from)) {
                                out[from + k] = (baseCode + k).toChar().toString() + base.drop(1)
                            }
                        } else {
                            val list = Regex("<([0-9A-Fa-f]+)>").findAll(target).map { hexToText(it.groupValues[1]) }.toList()
                            for ((k, s) in list.withIndex()) {
                                if (from + k <= to) out[from + k] = s
                            }
                        }
                    }
                }
                return PdfCMap(out)
            }

            /** Unicode из hex-строки (UTF-16BE, как требует спецификация). */
            fun hexToText(hex: String): String {
                if (hex.length < 2) return ""
                // UTF-16BE как в спецификации: единицы кода по четыре hex-цифры.
                // Нечётная длина — считаем как 8-битные коды (встречается в старых книгах).
                val width = if (hex.length % 4 == 0) 4 else 2
                val sb = StringBuilder(hex.length / width)
                var i = 0
                while (i + width <= hex.length) {
                    val code = hex.substring(i, i + width).toIntOrNull(16) ?: break
                    sb.append(code.toChar())
                    i += width
                }
                return sb.toString()
            }

            private val RANGE = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]+>|\\[[^\\]]*\\])")
        }
    }
}
