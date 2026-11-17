package com.bernkastel5.haqomu.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.lookup.LookupPopupActivity
import com.bernkastel5.haqomu.lookup.SelectionInfo
import com.bernkastel5.haqomu.ui.HaqomuPrefs

/**
 * Сервис доступности (Ф2, Ф3): плавающая кнопка у выделения и предложение-контекст.
 *
 * Что делает:
 *  - при смене выделения читает текст узла, берёт слово и предложение вокруг него;
 *  - режим [AccessibilityMode.Floating] — показывает маленькую кнопку у выделения,
 *    тап открывает штатный попап [LookupPopupActivity] с этим словом и контекстом;
 *  - режим [AccessibilityMode.Context] — кнопки нет, контекст запоминается в памяти
 *    для следующего «Словарь» из меню выделения (ACTION_PROCESS_TEXT);
 *  - при закрытии/прокрутке/смене окна кнопка убирается.
 *
 * Чего не делает (TZ §8, Ф2): не сохраняет и не передаёт текст экрана, ничего не
 * логирует, не читает поля паролей, не подписывается на события без выделения.
 * Выключен по умолчанию; включается только в системных настройках, приложение
 * показывает явное уведомление и объяснение на экране «Доступ к экрану».
 *
 * Приложения, которые не отдают текст в дерево доступности (Flutter/WebView/ридеры),
 * просто не вызывают кнопку — штатный путь PROCESS_TEXT остаётся как был (assessment §2).
 */
class HaqomuAccessibilityService : AccessibilityService() {

    private var mode: AccessibilityMode = AccessibilityMode.Off
    private var bubble: FloatingSelectionButton? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshMode()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // Только выделение текста и смена окон; содержимое по таймеру не опрашиваем.
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = AccessibilityServiceInfo.DEFAULT
        }
    }

    /**
     * Режим читается из настроек при каждом событии: пользователь может переключить
     * его на экране «Доступ к экрану», не трогая системный переключатель сервиса.
     * При выключенном режиме сервис не читает ни одного узла.
     */
    private fun refreshMode() {
        mode = HaqomuPrefs.accessibilityMode(this)
        SelectionHub.setEnabled(mode.isEnabled)
    }

    /**
     * Вид события сравниваем через if: подписка уже ограничена конфигом сервиса, а
     * AccessibilityEvent.eventType в Kotlin помечен как изменяемый — исчерпывающий
     * when по нему компилятор не гарантирует.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        refreshMode()
        if (mode == AccessibilityMode.Off) return
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            onSelectionChanged(e)
        } else {
            hideBubble()
        }
    }

    override fun onInterrupt() {
        hideBubble()
    }

    override fun onDestroy() {
        hideBubble()
        mainHandler.removeCallbacksAndMessages(null)
        SelectionHub.reset()
        super.onDestroy()
    }

    private fun onSelectionChanged(event: AccessibilityEvent) {
        val node = event.source
        if (node == null) {
            hideBubble()
            return
        }
        try {
            if (!node.isEnabled || node.isPassword) {
                hideBubble()
                return
            }
            // Текст узла есть не всегда: WebView/Compose/браузеры отдают выделение только
            // в событии. Смещения тоже не всегда валидны. SelectionLocator разбирает все
            // три случая, чтобы предложение-контекст не терялось (Ф3).
            val selected = event.text?.joinToString("") { it.toString() }
            val info = SelectionLocator.locate(
                nodeText = node.text,
                selectedText = selected,
                selStart = node.textSelectionStart,
                selEnd = node.textSelectionEnd,
                isPassword = node.isPassword,
            )
            if (info == null) {
                hideBubble()
                return
            }
            // Контекст запоминаем всегда: им пользуется и кнопка, и PROCESS_TEXT-попап.
            SelectionHub.remember(info)
            if (!FloatingButtonRules.shouldShow(mode, info.word.length)) {
                hideBubble()
                return
            }
            showBubble(info, boundsOf(node))
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    // Тип окна доступности проверяется системой: сервис уже включён пользователем.
    @SuppressLint("InlinedApi")
    private fun showBubble(info: SelectionInfo, anchor: Rect) {
        val button = bubble ?: FloatingSelectionButton(this).also { bubble = it }
        button.onTap = { word, sentence -> openPopup(word, sentence) }
        button.showAt(anchor, info.word, info.sentence)
    }

    private fun openPopup(word: String, sentence: String) {
        hideBubble()
        // Небольшая задержка: палец должен «отпустить» выделение, иначе системная панель
        // выделения закроет попап. Handler, а не View.postDelayed: окно кнопки уже убрано.
        mainHandler.postDelayed({
            val intent = LookupPopupActivity.newIntent(this, word, sentence).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                SelectionHub.clear()
            }
        }, 120L)
    }

    private fun hideBubble() {
        bubble?.hide()
    }
}

/**
 * Маленькая кнопка у выделения. Оформление по docs/design-guidelines.md: плоская
 * заливка, буква вместо иконки, без эмоджи, анимаций, теней и «стекла»; касания вне
 * кнопки не перехватываются (FLAG_NOT_FOCUSABLE, размер окна — только кружок).
 */
internal class FloatingSelectionButton(private val service: AccessibilityService) {

    /** Тап: слово и предложение выделения. */
    var onTap: ((String, String) -> Unit)? = null

    private val density = service.resources.displayMetrics.density
    private val size = (42 * density).toInt()
    private val margin = (8 * density).toInt()

    private var view: View? = null

    private val windowManager: WindowManager? =
        service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager

    fun showAt(anchor: Rect, word: String, sentence: String) {
        val wm = windowManager ?: return
        hide()
        val metrics = service.resources.displayMetrics
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            // Окно доступности: разрешение «поверх других приложений» не нужно,
            // и оно живёт ровно столько, сколько подключён сервис.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (anchor.right + margin).coerceIn(margin, (metrics.widthPixels - size - margin).coerceAtLeast(margin))
            y = ((anchor.top + anchor.bottom) / 2 - size / 2)
                .coerceIn(margin, (metrics.heightPixels - size - margin).coerceAtLeast(margin))
        }
        val bubble = SelectionBubbleView(service).apply {
            letter = service.getString(R.string.floating_button_letter)
            contentDescription = service.getString(R.string.floating_button_label)
            setOnClickListener { onTap?.invoke(word, sentence) }
        }
        try {
            wm.addView(bubble, layoutParams)
        } catch (_: Exception) {
            // Окно не добавилось (система запретила оверлей): кнопки не будет, попап
            // остаётся доступен через меню выделения.
            return
        }
        view = bubble
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
            // окно уже убрано системой
        }
    }
}

/** Кружок с буквой: плоская заливка, никаких иконок и градиентов. */
internal class SelectionBubbleView(context: android.content.Context) : View(context) {

    var letter: String = "H"

    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0E6E5C.toInt()
        style = android.graphics.Paint.Style.FILL
    }

    private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2FAF7.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
        isSubpixelText = true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val radius = minOf(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, fill)
        ink.textSize = radius * 0.95f
        val baseline = height / 2f - (ink.descent() + ink.ascent()) / 2f
        canvas.drawText(letter, width / 2f, baseline, ink)
    }
}
