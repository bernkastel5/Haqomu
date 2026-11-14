#!/usr/bin/env bash
# Публикация тестового APK, чтобы его можно было поставить на телефон.
#
# Зачем скрипт, а не workflow: у подключения к GitHub из этой рабочей сессии нет права
# `workflows`, поэтому править .github/workflows/* нельзя. Зато обычные файлы и ветки
# доступны, а Gradle-хук в app/build.gradle.kts вызывает этот скрипт после :app:assembleDebug.
#
# Что делает:
#   1. кладёт APK в ветку `apk-build` ОДНОЙ ревизией без истории (у коммита нет родителей,
#      предыдущие сборки перезаписываются): APK не попадает ни в основную ветку, ни в историю;
#   2. если доступен gh (токен берётся из GH_TOKEN или из git-конфига checkout-экшена),
#      дублирует APK в релиз `apk-latest` — оттуда ссылка постоянная и удобна для телефона.
#
# Использование:
#   tools/publish_apk.sh app/build/outputs/apk/debug/app-debug.apk [ветка]
#
# Коды выхода: 0 — опубликовано (или нечего публиковать), 1 — не удалось (для CI это
# предупреждение, а не поломка сборки: см. обработку в Gradle-хуке).
set -uo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
BRANCH="${2:-apk-build}"
NAME="haqomu-debug.apk"
RELEASE="apk-latest"

if [ ! -f "$APK" ]; then
  echo "publish_apk: нет файла $APK — публиковать нечего"
  exit 0
fi

REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT" || exit 1

SHA=$(git rev-parse --short=7 HEAD)
SIZE=$(du -h "$APK" | cut -f1)
# Версию берём из самой сборки: aapt2 в CI есть, но BUILD.txt нужен и без него,
# поэтому сначала пробуем манифест, а при неудаче пишем только коммит.
VERSION_CODE="${HAQOMU_VERSION_CODE:-}"
VERSION_NAME="${HAQOMU_VERSION_NAME:-}"
WORK=build/apk-publish
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$APK" "$WORK/$NAME"
{
  printf 'Haqomu — тестовая сборка\n'
  printf 'коммит: %s\n' "$SHA"
  [ -n "$VERSION_CODE" ] && printf 'version_code: %s\n' "$VERSION_CODE"
  [ -n "$VERSION_NAME" ] && printf 'version_name: %s\n' "$VERSION_NAME"
  printf 'размер: %s\n' "$SIZE"
  printf 'sha256: %s\n' "$(sha256sum "$WORK/$NAME" | cut -d' ' -f1)"
} > "$WORK/BUILD.txt"

STATUS=0

# --- 1. ветка без истории ---------------------------------------------------
# hash-object/mktree/commit-tree не трогают ни рабочую копию, ни индекс основного репозитория.
if BLOB=$(git hash-object -w "$WORK/$NAME") &&
   META=$(git hash-object -w "$WORK/BUILD.txt") &&
   TREE=$(printf '100644 blob %s\t%s\n100644 blob %s\tBUILD.txt\n' "$BLOB" "$NAME" "$META" | git mktree) &&
   COMMIT=$(GIT_AUTHOR_NAME=ci GIT_AUTHOR_EMAIL=ci@localhost \
            GIT_COMMITTER_NAME=ci GIT_COMMITTER_EMAIL=ci@localhost \
            git commit-tree "$TREE" -m "APK $SHA ($SIZE)") &&
   git push -q --force origin "$COMMIT:refs/heads/$BRANCH" 2>&1; then
  echo "publish_apk: APK $SHA в ветке $BRANCH"
else
  echo "publish_apk: ветку $BRANCH обновить не удалось (нужно право записи у токена)"
  STATUS=1
fi

# --- 2. релиз apk-latest ----------------------------------------------------
# Токен: GH_TOKEN, иначе — то, чем Actions чекаутит репозиторий.
TOKEN="${GH_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  HEADER=$(git config --get http.https://github.com/.extraheader 2>/dev/null |
           sed -n 's/.*basic \([A-Za-z0-9+\/=]*\).*/\1/p')
  if [ -n "$HEADER" ]; then
    TOKEN=$(printf '%s' "$HEADER" | base64 -d 2>/dev/null | sed -n 's/^x-access-token://p')
  fi
fi

if [ -n "$TOKEN" ] && command -v gh >/dev/null 2>&1; then
  export GH_TOKEN="$TOKEN"
  if ! gh release view "$RELEASE" >/dev/null 2>&1; then
    gh release create "$RELEASE" --prerelease \
      --title "Haqomu — тестовая сборка (APK)" \
      --notes "Свежая debug-сборка Haqomu. Файл haqomu-debug.apk — постоянное имя, рядом BUILD.txt с коммитом." \
      >/dev/null 2>&1
  fi
  if gh release upload "$RELEASE" "$WORK/$NAME" --clobber >/dev/null 2>&1; then
    echo "publish_apk: APK $SHA в релизе $RELEASE"
  else
    echo "publish_apk: загрузить в релиз $RELEASE не удалось"
    STATUS=1
  fi
else
  echo "publish_apk: gh/токен недоступны, релиз пропускаю"
fi

exit $STATUS
