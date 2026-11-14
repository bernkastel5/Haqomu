package com.bernkastel5.haqomu.service

/**
 * Режим сервиса доступности (настройка пользователя, выключен по умолчанию — Ф2).
 *
 * [Floating] — плавающая кнопка у выделения; [Context] — кнопки нет, сервис только
 * запоминает предложение вокруг выделения, чтобы штатный попап PROCESS_TEXT показал
 * контекст и подставил его в карточку (Ф3).
 */
enum class AccessibilityMode {
    Off,
    Floating,
    Context;

    val isEnabled: Boolean get() = this != Off

    companion object {
        fun fromStored(value: String?): AccessibilityMode = when (value) {
            "floating" -> Floating
            "context" -> Context
            else -> Off
        }

        fun storedValue(mode: AccessibilityMode): String = when (mode) {
            Floating -> "floating"
            Context -> "context"
            Off -> "off"
        }
    }
}

/**
 * Правила показа плавающей кнопки. Чистая логика без Android — покрыта JVM-тестами.
 */
object FloatingButtonRules {

    /** Короче этого выделение кнопку не показываем: одиночный тап по слову её не дёргает. */
    const val MIN_SELECTION_CHARS = 2

    /** Кнопка видна только в режиме [AccessibilityMode.Floating] и при непустом выделении. */
    fun shouldShow(mode: AccessibilityMode, selectionLength: Int): Boolean =
        mode == AccessibilityMode.Floating && selectionLength >= MIN_SELECTION_CHARS

    /**
     * Годен ли текст узла: подсказки, пароли и пустые узлы не читаем.
     * [isPassword] — AccessibilityNodeInfo.isPassword.
     */
    fun isUsableText(text: CharSequence?, isPassword: Boolean): Boolean {
        if (isPassword) return false
        val value = text?.toString() ?: return false
        return value.isNotBlank()
    }
}

/**
 * Сопоставление id сервиса из системного списка с нашим классом. Чистая функция:
 * система отдаёт id либо как «пакет/.Класс», либо как «пакет/полное.имя.Класса».
 */
object AccessibilityServiceId {

    fun matches(infoId: String?, packageName: String, className: String): Boolean {
        val id = infoId?.trim().orEmpty()
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.length - 1) return false
        val pkg = id.substring(0, slash)
        if (pkg != packageName) return false
        val rawClass = id.substring(slash + 1)
        val fullClass = if (rawClass.startsWith('.')) pkg + rawClass else rawClass
        return fullClass == className
    }
}
