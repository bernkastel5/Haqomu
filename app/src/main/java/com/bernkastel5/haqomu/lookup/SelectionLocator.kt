package com.bernkastel5.haqomu.lookup

import com.bernkastel5.haqomu.service.FloatingButtonRules

/**
 * Поиск выделения в тексте узла, когда система не дала готовых смещений (Ф3).
 *
 * Почему это нужно. `TYPE_VIEW_TEXT_SELECTION_CHANGED` в реальных приложениях приходит
 * по-разному: у своих TextView есть и текст узла, и `textSelectionStart/End`, а у
 * WebView, Compose и части браузеров текст узла пустой, а выделенный текст лежит только
 * в событии (`event.text`). Раньше сервис в этом случае молча уходил: предложение
 * «не считывалось», и попап показывал словарный порядок без контекста.
 *
 * Здесь выделение восстанавливается по самому факту: если система дала смещения — берём
 * их, иначе ищем выделенный текст в тексте узла. Если и это невозможно (текста узла нет
 * вовсе) — контекста нет, но слово всё равно обслуживается: [SelectionCapture] умеет
 * работать по одному выделенному фрагменту.
 *
 * Чистая логика без Android: покрыто JVM-тестами.
 */
object SelectionLocator {

    /**
     * Выделение по данным события.
     *
     * @param nodeText полный текст узла (может быть null: приложение не отдаёт текст)
     * @param selectedText выделенный фрагмент из события (может быть null)
     * @param selStart смещение начала из узла, отрицательное — неизвестно
     * @param selEnd смещение конца из узла, отрицательное — неизвестно
     * @param isPassword поле пароля: такие узлы не обслуживаются (TZ §8)
     */
    fun locate(
        nodeText: CharSequence?,
        selectedText: CharSequence?,
        selStart: Int,
        selEnd: Int,
        isPassword: Boolean = false,
    ): SelectionInfo? {
        if (!FloatingButtonRules.isUsableText(nodeText, isPassword) &&
            !FloatingButtonRules.isUsableText(selectedText, isPassword)
        ) {
            return null
        }
        val full = nodeText?.toString()
        val selected = selectedText?.toString()

        // 1. Система дала корректные смещения — это самый надёжный путь.
        if (full != null && selStart in 0..full.length && selEnd in selStart..full.length && selEnd > selStart) {
            SelectionCapture.capture(full, selStart, selEnd)?.let { return it }
        }
        // 2. Смещения нет, но есть текст узла и выделенный фрагмент: ищем фрагмент.
        if (full != null && !selected.isNullOrBlank()) {
            val at = indexOfSelection(full, selected)
            if (at >= 0) {
                SelectionCapture.capture(full, at, at + selected.length)?.let { return it }
            }
        }
        // 3. Текста узла нет: контекста не будет, но слово обязано обслуживаться.
        if (!selected.isNullOrBlank()) {
            return SelectionCapture.capture(selected, 0, selected.length)
        }
        return null
    }

    /**
     * Позиция выделенного фрагмента в тексте узла. Пробелы в событии могут быть
     * схлопнуты, поэтому сначала ищем как есть, затем — по «скелету» без пробелов.
     */
    fun indexOfSelection(full: String, selected: String): Int {
        val direct = full.indexOf(selected)
        if (direct >= 0) return direct
        val trimmed = selected.trim()
        if (trimmed.isEmpty()) return -1
        val exact = full.indexOf(trimmed)
        if (exact >= 0) return exact

        val skeleton = trimmed.filterNot { it.isWhitespace() }
        if (skeleton.isEmpty()) return -1
        var i = 0
        while (i < full.length) {
            if (full[i].isWhitespace()) {
                i++
                continue
            }
            var j = i
            var k = 0
            while (j < full.length && k < skeleton.length) {
                if (full[j].isWhitespace()) {
                    j++
                    continue
                }
                if (full[j] != skeleton[k]) break
                j++
                k++
            }
            if (k == skeleton.length) return i
            i++
        }
        return -1
    }
}
