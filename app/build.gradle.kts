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
 * CI прогоняет `:app:assembleDebug`; после успешной сборки этот хук кладёт готовый APK
 * в ветку `apk-build` — одной ревизией без истории (у коммита нет родителей), через
 * низкоуровневые `git hash-object`/`mktree`/`commit-tree`, чтобы не трогать рабочую копию
 * и индекс основного репозитория. Так APK не попадает ни в основную ветку, ни в историю.
 * Забирается оттуда как обычный файл; инструкция — в README.
 *
 * Работает только в CI и при наличии токена; локальные сборки ничего не публикуют.
 * Отключается флагом `-PpublishApk=false`.
 */
val publishApkEnabled = providers.gradleProperty("publishApk").orNull != "false" &&
    System.getenv("CI") == "true"

val publishDebugApk = tasks.register("publishDebugApk") {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val repo = rootDir
    // APK всегда попадает в эту ветку; при наличии токена с правом release — ещё и в релиз.
    val branch = "apk-build"
    doLast {
        // Публикация — вспомогательный шаг: её сбой не должен валить сборку.
        runCatching {
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) {
            println("::error::publishDebugApk: APK не найден: ${apkFile.absolutePath}")
            return@doLast
        }
        val work = File(repo, "build/apk-publish")
        work.deleteRecursively()
        work.mkdirs()
        apkFile.copyTo(File(work, "haqomu-debug.apk"), overwrite = true)
        val stamp = File(work, "BUILD.txt")
        val sha = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(repo).start().inputStream.bufferedReader().readText().trim()
        stamp.writeText("Haqomu debug APK\ncommit: $sha\n")
        val script = """
            set -e
            blob=${'$'}(git hash-object -w build/apk-publish/haqomu-debug.apk)
            meta=${'$'}(git hash-object -w build/apk-publish/BUILD.txt)
            tree=${'$'}(printf '100644 blob %s\thaqomu-debug.apk\n100644 blob %s\tBUILD.txt\n' "${'$'}blob" "${'$'}meta" | git mktree)
            commit=${'$'}(GIT_AUTHOR_NAME=ci GIT_AUTHOR_EMAIL=ci@localhost GIT_COMMITTER_NAME=ci GIT_COMMITTER_EMAIL=ci@localhost git commit-tree "${'$'}tree" -m "APK $sha")
            git push --force origin "${'$'}commit:refs/heads/$branch"
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", script)
            .directory(repo)
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        // Сообщения через workflow-команды: они видны в аннотациях прогона,
        // даже когда полный лог недоступен.
        if (process.waitFor() == 0) {
            println("::notice::publishDebugApk: APK $sha в ветке $branch")
        } else {
            println("::error::publishDebugApk: ветка не обновлена: " + out.replace("\n", " | "))
        }
        // Дополнительно — в релиз apk-latest, если у токена есть право contents:write.
        val release = ProcessBuilder(
            "gh", "release", "upload", "apk-latest",
            "build/apk-publish/haqomu-debug.apk#haqomu-debug.apk", "--clobber",
        ).directory(repo).redirectErrorStream(true).start()
        val releaseOut = release.inputStream.bufferedReader().readText().trim()
        if (release.waitFor() == 0) {
            println("::notice::publishDebugApk: APK $sha в релизе apk-latest")
        } else {
            println("::warning::publishDebugApk: релиз недоступен: " + releaseOut.replace("\n", " | "))
        }
        }.onFailure {
            println("::error::publishDebugApk: исключение: " + (it.message ?: it.javaClass.name))
        }
    }
}

// Хук привязан к задаче сборки debug-варианта, чтобы не зависеть от имени агрегата.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    if (publishApkEnabled) finalizedBy(publishDebugApk)
}
