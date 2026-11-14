#!/usr/bin/env python3
"""
Проверка собранного пака Hans Wehr (data/packs/hanswehr/hw_pack.sqlite).

Что проверяет:
  1. схему и целостность (обязательные таблицы/колонки, meta, счётчики по kind)
  2. дубли-омографы (одно слово -> несколько статей)
  3. целостность привязки к корням (root_id существует, корень сам на себя)
  4. качество полей (romanization / idioms / plurals / body)
  5. симуляцию лукапа по словоформам: клитики + нормализация + индекс форм
     (это "деинфлектор lite" — показывает, что пак отдаёт без нейросетей и морфологии)

Запуск:
    python3 tools/verify_pack.py data/packs/hanswehr/hw_pack.sqlite
Выходной код 1, если не пройдены базовые проверки.
"""
from __future__ import annotations

import re
import sqlite3
import sys
import unicodedata
from collections import Counter

REQUIRED_COLUMNS = {
    "id", "kind", "headword", "headword_vocalized", "headword_norm", "romanization",
    "body", "idioms", "verb_forms", "plurals", "alt_root", "root_id",
    "quran_occurrence", "see_entry_id", "flags",
}
VALID_KINDS = {"root", "word", "xref", "xref_alpha", "empty"}

DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u0640]")

PREFIXES = ["وال", "بال", "كال", "فال", "ال", "لل", "و", "ف", "ب", "ك", "ل", "س"]
SUFFIXES = ["ها", "هم", "هن", "كما", "كم", "كن", "نا", "ون", "ين", "ات", "ان",
            "تين", "تان", "وا", "تم", "تهم", "ني", "ه", "ي", "ت"]

# контрольный набор: форма -> чего ждём (для отчёта, не для автоматической сверки смысла)
CONTROL = [
    ("مكتبة", True), ("الكتاب", True), ("البيوت", True), ("المدارس", True),
    ("مستشفيات", True), ("كتب", True), ("مدرسون", True), ("وبالكتاب", True),
    ("سيارات", True), ("لغة", True), ("معلمون", True), ("جامعة", True),
    ("قلم", True), ("ماء", True), ("الطريق", True),
]


def nk(s: str) -> str:
    """Нормализация для поиска: без огласовок, унифицированные алеф/йа/та-марбута."""
    s = DIAC.sub("", unicodedata.normalize("NFC", s or ""))
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = s.replace("ة", "ه")
    return re.sub(r"[^\u0600-\u06FF]", "", s)


def candidates(word: str) -> list[str]:
    """Рекурсивное отсечение клитик (префиксы/суффиксы), до 2 уровней."""
    base = nk(word)
    out: list[str] = []

    def rec(w: str, depth: int):
        if w not in out:
            out.append(w)
        if depth >= 2 or len(w) <= 3:
            return
        for p in PREFIXES:
            if w.startswith(p) and len(w) - len(p) >= 3:
                rec(w[len(p):], depth + 1)
        # правильные ж.р. множественные: سيارة -> سيارات (в Wehr часто "pl. -āt")
        if w.endswith("ات") and len(w) - 2 >= 3:
            rec(w[:-2] + "ه", depth + 1)
        for s in SUFFIXES:
            if w.endswith(s) and len(w) - len(s) >= 3:
                rec(w[:-len(s)], depth + 1)

    rec(base, 0)
    return out


def lookup(con, form: str):
    """Ищем форму в индексе: сначала как заголовок статьи, потом как множественное."""
    order = "CASE f.kind WHEN 'word' THEN 0 WHEN 'root' THEN 1 WHEN 'plural' THEN 2 ELSE 3 END"
    rows = list(con.execute(
        f"""SELECT e.id, e.headword, e.kind, e.romanization, e.body, e.root_id, r.headword,
                  f.kind
           FROM forms f JOIN entries e ON e.id = f.entry_id
           LEFT JOIN entries r ON r.id = e.root_id
           WHERE f.form = ? ORDER BY {order}, e.id LIMIT 3""", (form,)))
    return rows


def main(path: str) -> int:
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    ok = True

    print("=" * 72)
    print("1. СХЕМА")
    print("=" * 72)
    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    print("  таблицы:", sorted(tables))
    for need in ("entries", "forms", "meta"):
        if need not in tables:
            print(f"  !! нет таблицы {need}")
            ok = False
    cols = {r[1] for r in con.execute("PRAGMA table_info(entries)")}
    missing = REQUIRED_COLUMNS - cols
    print("  обязательные колонки:", "все на месте" if not missing else f"НЕТ {sorted(missing)}")
    ok &= not missing
    meta = dict(con.execute("SELECT k, v FROM meta"))
    print("  meta:", {k: meta[k] for k in ("source", "entries", "roots") if k in meta})

    print()
    print("=" * 72)
    print("2. СОСТАВ")
    print("=" * 72)
    kinds = Counter(dict(con.execute("SELECT kind, COUNT(*) FROM entries GROUP BY kind")))
    for k, v in kinds.most_common():
        print(f"  {k:12s} {v:6d}")
        if k not in VALID_KINDS:
            print(f"  !! неизвестный kind: {k}")
            ok = False
    total = sum(kinds.values())
    print(f"  {'всего':12s} {total:6d}")
    forms_total, forms_distinct = con.execute("SELECT COUNT(*), COUNT(DISTINCT form) FROM forms").fetchone()
    print(f"  форм в индексе: {forms_total}, уникальных: {forms_distinct}")

    print()
    print("=" * 72)
    print("3. ОМОГРАФЫ")
    print("=" * 72)
    dup = con.execute("""SELECT COUNT(*) FROM (
                          SELECT headword_norm FROM entries GROUP BY headword_norm HAVING COUNT(*) > 1)""").fetchone()[0]
    print(f"  слов с 2+ статьями: {dup}")
    for hw, n, sample in con.execute("""SELECT headword, COUNT(*) c, GROUP_CONCAT(DISTINCT kind)
                                        FROM entries GROUP BY headword_norm HAVING c > 1
                                        ORDER BY c DESC LIMIT 5"""):
        print(f"    {hw:10s} x{n}  ({sample})")
    print("  -> при лукапе возможна неверная статья; нужен ранкер на клиенте")

    print()
    print("=" * 72)
    print("4. ПРИВЯЗКА К КОРНЯМ")
    print("=" * 72)
    broken = con.execute("""SELECT COUNT(*) FROM entries e WHERE e.root_id IS NOT NULL
                            AND NOT EXISTS (SELECT 1 FROM entries r WHERE r.id = e.root_id)""").fetchone()[0]
    self_root = con.execute("SELECT COUNT(*) FROM entries WHERE root_id = id").fetchone()[0]
    no_root = con.execute("SELECT COUNT(*) FROM entries WHERE root_id IS NULL").fetchone()[0]
    unverified = con.execute("SELECT COUNT(*) FROM entries WHERE flags LIKE '%root-unverified%'").fetchone()[0]
    print(f"  root_id указывает в пустоту: {broken}")
    print(f"  статьи, где корень = сама статья: {self_root}")
    print(f"  без корня: {no_root}")
    print(f"  помечено root-unverified (слово не содержит букв корня): {unverified}")
    ok &= broken == 0

    print()
    print("=" * 72)
    print("5. КАЧЕСТВО ПОЛЕЙ")
    print("=" * 72)
    n = total
    for label, sql in [
        ("romanization", "SELECT COUNT(*) FROM entries WHERE romanization IS NOT NULL AND romanization != ''"),
        ("idioms", "SELECT COUNT(*) FROM entries WHERE idioms IS NOT NULL AND idioms != ''"),
        ("plurals", "SELECT COUNT(*) FROM entries WHERE plurals IS NOT NULL AND plurals != ''"),
        ("verb_forms", "SELECT COUNT(*) FROM entries WHERE verb_forms IS NOT NULL AND verb_forms != ''"),
        ("body", "SELECT COUNT(*) FROM entries WHERE body IS NOT NULL AND body != ''"),
        ("есть остатки 'see'", "SELECT COUNT(*) FROM entries WHERE body LIKE '% see %'"),
        ("body начинается с 'and'", "SELECT COUNT(*) FROM entries WHERE body LIKE 'and %'"),
        ("огласовка в заголовке", "SELECT COUNT(*) FROM entries WHERE headword_vocalized != ''"),
    ]:
        v = con.execute(sql).fetchone()[0]
        print(f"  {label:26s} {v:6d}  ({100*v/n:5.1f}%)")

    print()
    print("=" * 72)
    print("6. СИМУЛЯЦИЯ ЛУКАПА (клитики + нормализация, без морфологии)")
    print("=" * 72)
    hit = 0
    for word, expected in CONTROL:
        found = None
        used = None
        for cand in candidates(word):
            rows = lookup(con, cand)
            if rows:
                found, used = rows[0], cand
                break
        if found:
            hit += 1
            eid, hw, kind, rom, body, root_id, root_hw, fkind = found
            print(f"  {word:12s} -> '{used}' -> #{eid} {hw} [{fkind}] rom={rom or '—'} root={root_hw or '—'}")
            print(f"                 {(body or '')[:78]}")
        else:
            print(f"  {word:12s} -> НЕ НАЙДЕНО")
    print()
    print(f"  найдено {hit}/{len(CONTROL)} = {100*hit/len(CONTROL):.0f}%")
    print("  (провалы ожидаемы там, где Wehr держит слово внутри корневой статьи)")
    print()
    print("ИТОГ:", "OK" if ok else "ЕСТЬ ОШИБКИ")
    return 0 if ok else 1


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "data/packs/hanswehr/hw_pack.sqlite"
    sys.exit(main(p))
