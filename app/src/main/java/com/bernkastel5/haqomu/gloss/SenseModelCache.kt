package com.bernkastel5.haqomu.gloss

import android.content.Context
import com.bernkastel5.haqomu.ui.HaqomuPrefs
import java.io.File

/**
 * Одна загруженная модель на процесс (Ф10). Загрузка ONNX-графа стоит сотни
 * миллисекунд, попап живёт секунды — держим сессию открытой и закрываем при
 * смене модели или её удалении.
 *
 * Если модель не установлена, выключена в настройках или не загрузилась,
 * возвращается null, и попап работает по словарному порядку (штатный режим).
 */
object SenseModelCache {

    @Volatile
    private var loaded: SenseModel? = null

    @Volatile
    private var loadedStamp: String? = null

    /** Модель для попапа; null — модели нет или она сломана. */
    fun get(context: Context): SenseModel? {
        if (!HaqomuPrefs.senseModelEnabled(context)) return null
        if (!ModelStore.isInstalled(context)) return null
        val stamp = stampOf(context)
        loaded?.let { if (loadedStamp == stamp) return it }
        synchronized(this) {
            loaded?.let { if (loadedStamp == stamp) return it }
            closeQuietly()
            val meta = ModelStore.meta(context) ?: return null
            val model = try {
                OnnxSenseModel.open(ModelStore.dir(context), meta)
            } catch (_: Throwable) {
                return null
            }
            loaded = model
            loadedStamp = stamp
            return model
        }
    }

    /** Сбрасывает кэш после установки/удаления модели. */
    fun invalidate() {
        synchronized(this) { closeQuietly() }
    }

    private fun stampOf(context: Context): String {
        val dir = ModelStore.dir(context)
        val model = File(dir, ModelBundle.FILE_MODEL)
        return "${model.absolutePath}:${model.length()}:${model.lastModified()}"
    }

    private fun closeQuietly() {
        try {
            loaded?.close()
        } catch (_: Throwable) {
            // закрытие не должно мешать установке новой модели
        }
        loaded = null
        loadedStamp = null
    }
}
