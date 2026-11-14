# ADR-0005: Kotlin-порт конвертера calima → camel_pack.sqlite

Дата: 2026-11-14 · Статус: принято · Ветка: `arena/609280e4-haqomu`

## Контекст

ADR-0003 выбрал путь: calima-msa-r13 (текстовый morphology.db) → SQLite → нативный
SQLite на Android. Эталон — tools/build_camel_pack.py (selftest OK). D-019 одобряет
кнопку «Подготовить морфологию» прямо в мастере импорта, без ПК.

## Решение

1. **Потоковая архитектура.** `CamelMorphologyParser` не собирает пак в память:
   `parse(lines, sink)` отдаёт StemEntry/AffixEntry/CompatEntry по одной в `PackSink`.
   Android-запись (`AndroidCamelPackWriter`) пишет в SQLite внутри одной транзакции
   (compileStatement, ~2М INSERT), индексы и meta — в конце, VACUUM после коммита.
   На 40 МБ дамп это безопасно даже на слабых устройствах.
2. **Source stream.** Пользователь выбирает либо официальный `.zip` из релиза CAMeL-Lab
   (ищем запись `morphology.db` в ZipInputStream, без распаковки на диск), либо сам
   `morphology.db` (тогда выделять ничего не нужно).
3. **Отличия от Python-эталона (осознанные):** поля trim-ятся; JSON аффиксов собирается
   руками (org.json недоступен в JVM-тестах); строки прогресса — каждые 50 000 строк;
   `keep_feats` не портирован (приложение его не использует); meta пишется с датой сборки.
4. **Сохранено 1:1:** секции и токенизация, `stripLex` (split по `_-`, первый кусок),
   правило lex_logprob (нет/битое → -99.0), порядок merge и имена таблиц/индексов/мета-ключей
   (`idx_stems_stem`, `idx_affixes_form`, `idx_compat_ab`; meta: schema_version, source,
   license, build_date, счётчики). Quirk «root:ذ ه ب → root="ذ"» сохранён намеренно:
   сверка поведения с Python важнее косметики.
5. **Проверка без реальной calima:** JVM-тесты на синтетическом дампе (тот же, что в
   `tools/build_camel_pack.py --selftest`) — поля, счётчики, max sizes, counts, meta,
   stripLex, экранирование JSON, битый ввод. Прогон на реальной calima (размер, время)
   остаётся за заказчиком (№15).

## Последствия

- Экран «Импорт паков» получил секцию «Морфология CAMeL»: статус (meta, O(1)),
  кнопка подготовки, прогресс по строкам, честные ошибки; при сбое частичный файл удаляется.
- `MorphologyPack.status()` читает только meta, без сканов таблицы.
- При следующем шаге (фаза 5/6) `AndroidSqliteCamelStore` открывает этот файл из
  `filesDir/camel_pack.sqlite`.
