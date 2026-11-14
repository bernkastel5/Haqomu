plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bernkastel5.haqomu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bernkastel5.haqomu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Release APK for GitHub Releases: signing happens in phase 10
            // with a keystore kept outside git (see docs/adr/0002).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // ONNX Runtime тянет нативные .so для нескольких ABI; лишние не нужны.
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Модель разрешения смыслов (Ф10) — опциональная, грузится в рантайме; в APK весов нет.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}

/**
 * Публикация тестового APK для проверки на телефоне.
 *
 * После успешного `:app:assembleDebug` в CI вызывается `tools/publish_apk.sh`: он кладёт APK
 * в ветку `apk-build` (одной ревизией без истории) и, если доступен токен, в релиз `apk-latest`.
 * Ветку и историю основного репозитория это не затрагивает; APK в git-историю не попадает.
 * Локальные сборки ничего не публикуют; отключается флагом `-PpublishApk=false`.
 */
val publishApkEnabled = providers.gradleProperty("publishApk").orNull != "false" &&
    System.getenv("CI") == "true"

val publishDebugApk = tasks.register("publishDebugApk") {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val repo = rootDir
    doLast {
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) {
            println("::warning::publishDebugApk: APK не найден, пропускаю")
            return@doLast
        }
        // Публикация — вспомогательный шаг: её сбой не валит сборку и не красит прогон.
        val process = runCatching {
            ProcessBuilder("bash", "tools/publish_apk.sh", apkFile.absolutePath)
                .directory(repo).redirectErrorStream(true).start()
        }.getOrNull()
        if (process == null) {
            println("::warning::publishDebugApk: скрипт не запустился")
            return@doLast
        }
        val out = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        println(out)
        if (code == 0) {
            println("::notice::publishDebugApk: APK опубликован")
        } else {
            println("::warning::publishDebugApk: опубликовать не удалось (см. лог шага)")
        }
    }
}

// Хук привязан к задаче сборки debug-варианта, чтобы не зависеть от имени агрегата.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    if (publishApkEnabled) finalizedBy(publishDebugApk)
}
