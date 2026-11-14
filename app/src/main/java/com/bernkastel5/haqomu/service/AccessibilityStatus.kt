package com.bernkastel5.haqomu.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Системное состояние сервиса доступности: включён ли, как открыть настройки.
 * Только чтение — приложение не включает сервис за пользователя (Ф2, TZ §8).
 */
object AccessibilityStatus {

    /** Включён ли сервис Haqomu в системных настройках доступности. */
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expected = ComponentName(context, HaqomuAccessibilityService::class.java)
        val list = manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return list.any { info ->
            AccessibilityServiceId.matches(info.id, context.packageName, expected.className)
        }
    }

    /** Системный экран списка сервисов доступности. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
