plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Диагностика сборки в CI.
 *
 * Полные логи прогона из рабочей среды недоступны, а разбирать падения надо быстро:
 * упавшая задача печатает сообщение workflow-командой `::error::`, и оно попадает
 * в аннотации прогона (их читать можно). Ничего не публикует и не меняет.
 */
gradle.taskGraph.whenReady {
    if (System.getenv("CI") != "true") return@whenReady
    allTasks.forEach { task ->
        task.doFirst {
            // печатаем только вход в «дорогие» задачи, чтобы не засорять аннотации
            if (name in setOf("compileDebugKotlin", "testDebugUnitTest", "assembleDebug")) {
                println("::notice::старт задачи $path")
            }
        }
    }
}

gradle.taskGraph.afterTask { task ->
    if (System.getenv("CI") != "true") return@afterTask
    task.failure?.let { failure ->
        val message = (failure.message ?: failure.javaClass.name).lineSequence().take(6).joinToString(" | ")
        println("::error::задача ${task.path} упала: $message")
    }
}
