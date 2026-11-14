plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Диагностика сборки в CI.
 *
 * Полные логи прогона из рабочей среды недоступны, а разбирать падения надо быстро.
 * Поэтому после каждой задачи печатается одна строка статуса workflow-командой:
 * на успехе — `::notice::`, на падении — `::error::` с текстом ошибки. Аннотации прогона
 * читаются через API, так что причина видна без доступа к логам.
 */
gradle.taskGraph.afterTask { task ->
    if (System.getenv("CI") != "true") return@afterTask
    val failure = task.failure
    if (failure == null) {
        if (task.name in setOf("compileDebugKotlin", "testDebugUnitTest", "assembleDebug", "publishDebugApk")) {
            println("::notice::ok ${task.path}")
        }
    } else {
        val message = (failure.message ?: failure.javaClass.name)
            .lineSequence().filter { it.isNotBlank() }.take(4).joinToString(" | ")
        println("::error::fail ${task.path}: $message")
    }
}
