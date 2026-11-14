# ADR 0001: стек и версии сборки

Статус: принято 2026-11-14 · Ветка обсуждения: PROJECT_STATE.md §5, фаза 1.

## Контекст

Нужен воспроизводимый каркас Android-приложения Haqomu. Ограничения: minSdk 26 (TZ §2),
Kotlin, полностью офлайн-работа, репозиторий публичный (ничего лицензионно грязного
в git), дизайн по docs/design-guidelines.md.

## Решение

- Kotlin 2.0.21, плагин `org.jetbrains.kotlin.plugin.compose` (без матрицы ext-версий компилятора).
- Jetpack Compose (BOM 2024.12.01), Material 3, **своя палитра**, dynamic color выключен.
- AGP 8.7.3, Gradle 8.9 (ставится setup-gradle в CI; wrapper намеренно не закоммичен,
  генерируется локально: `gradle wrapper --gradle-version 8.9`).
- compileSdk/targetSdk 35, minSdk 26, JDK 17, один модуль `:app`.
- RTL-first (`android:supportsRtl="true"`), арабская гарнитура с полной огласовкой — фаза 3.
- Проверка сборки — GitHub Actions (`.github/workflows/build.yml`): assembleDebug +
  testDebugUnitTest + py_compile эталонных инструментов. Локальная песочница Google Maven
  и дистрибутивы Gradle не достаётся, поэтому CI — единственный авторитетный прогон.
  Версии в `gradle/libs.versions.toml` пины, обновление — отдельным ADR/PR.

## Последствия

- Версии можно безопасно поднимать, только когда CI зелёный на main.
- Release-подпись не настроена (фаза 10): keystore вне git, в репо только код и инструкции.
- Первую локальную проверку сборки делает CI после пуша ветки или машина заказчика.
