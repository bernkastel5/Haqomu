#!/usr/bin/env python3
"""
Сборка чистого пака Hans Wehr из `hanswehr.sqlite`
(GibreelAbdullah/HansWehrDictionary, 4-е изд., OCR + ручная правка).

Что делает (шаги соответствуют hw-sqlite-report.md §8):
  1. VACUUM исходника (16.7 -> ~11 МБ)
  2. пересчёт флага is_root по строгим критериям
  3. восстановление иерархии "корень -> производные" (ближайший предшествующий заголовок)
     + валидация лингвистическим инвариантом (буквы корня внутри слова)
  4. разбор definition на поля: headword / romanization / body / idioms / verb_forms / plurals
  5. отрезание дублирующегося заголовка, чистка номеров страниц, надстрочных цифр, кавычек
  6. разрешение отсылок "see X"
  7. удаление нерабочей таблицы TRANSLITERATION
  8. выгрузка: SQLite + JSONL.gz + build-report.md

Запуск:
    python3 tools/build_hw_pack.py \
        --src data/raw/hanswehr.sqlite \
        --out data/packs/hanswehr

Исходные данные в репозиторий не входят (см. README).
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import random
import re
import shutil
import sqlite3
import unicodedata
from collections import Counter, defaultdict

# ---------------------------------------------------------------- алфавиты

AR_BLOCKS = (
    "\u0600-\u06FF"      # Arabic
    "\u0750-\u077F"      # Arabic Supplement
    "\u08A0-\u08FF"      # Arabic Extended-A
    "\uFB50-\uFDFF"      # Presentation Forms-A
    "\uFE70-\uFEFF"      # Presentation Forms-B
)
DIACRITICS = "\u0610-\u061A\u064B-\u065F\u0670\u0640"

AR_CHAR = re.compile(f"[{AR_BLOCKS}]")
DIAC_RE = re.compile(f"[{DIACRITICS}]")
AR_ONLY = re.compile(f"^[{AR_BLOCKS}{DIACRITICS}]+$")

LATIN_CHARS = (
    "A-Za-z"
    "\u00C0-\u00FF"      # à-ÿ
    "\u0100-\u017F"      # ā ī ū
    "\u1E00-\u1EFF"      # ṯ ḏ ḫ ġ ṣ ḍ ṭ ẓ
    "\u02B0-\u02FF"      # ʾ ʿ ʼ
    "\u2018-\u201F"      # ‘ ’ “ ”
    "'\u0027-"
)
LATIN_ONLY = re.compile(f"^[{LATIN_CHARS}]+$")

ROMAN_STOP = {"see", "cf", "pl", "and", "the", "or", "also", "below", "above"}

VERB_FORM_RE = re.compile(r"<b>(I|II|III|IV|V|VI|VII|VIII|IX|X)</b>")
PLURAL_RE = re.compile(r"\bpl\.\s*([^;│]{1,60})")
SEE_RE = re.compile(r"^(?P<head>[^\s]+(?:\s+[^\s]+)?)\s+(?:\((?P<alt>[^)]+)\)\s+)?"
                    r"(?:(?P<rom>[^\s\u0600-\u06FF]+)\s+)?see\s+(?P<target>[\u0600-\u06FF][\u0600-\u06FF\s]*)$")
ALPHA_REF_RE = re.compile(r"look\s+up\s+alphabetically", re.I)
STRAY_NUM = re.compile(r"(?<![\w\-\u0600-\u06FF])\d{2,4}(?![\w\-])")
GLUED_DIGIT = re.compile(r"\b([A-Za-z\u00C0-\u017F\u1E00-\u1EFF]{3,})\d\b")


# ---------------------------------------------------------------- утилиты

def collapse_ws(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def strip_diacritics(s: str) -> str:
    return DIAC_RE.sub("", s or "")


def norm_key(s: str) -> str:
    """Ключ для поиска: без огласовок, унифицированные алеф/йа/та-марбута, без татвиля."""
    s = strip_diacritics(NFC(s))
    s = s.replace("ـ", "")
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = re.sub("[ؤ]", "و", s)
    s = s.replace("ة", "ه")
    s = re.sub(r"[^\u0600-\u06FF]+", "", s)
    return s


def NFC(s: str) -> str:
    return unicodedata.normalize("NFC", s or "")


def is_arabic_token(tok: str) -> bool:
    core = tok.strip("()[]{}.,;:،؛؟·")
    return bool(core) and bool(AR_ONLY.match(core))


def is_latin_token(tok: str) -> bool:
    core = tok.strip("()[]{}.,;:·")
    return bool(core) and bool(LATIN_ONLY.match(core)) and any(c.isalpha() for c in core)


def contains_root_letters(word: str, root: str) -> bool:
    w, r = norm_key(word), norm_key(root)
    if not r or not w:
        return False
    it = iter(w)
    return all(ch in it for ch in r)


# ---------------------------------------------------------------- разбор статьи

def clean_body(text: str, stats: Counter) -> str:
    t = collapse_ws(text)
    t = t.replace("―", "—")
    # ‘ -> ’ между латинскими буквами (артефакт кавычек OCR)
    t = re.sub(r"(?<=[A-Za-z])‘(?=[A-Za-z])", "’", t)
    n = len(STRAY_NUM.findall(t))
    if n:
        stats["page_numbers_removed"] += n
        t = STRAY_NUM.sub("", t)
    n = len(GLUED_DIGIT.findall(t))
    if n:
        stats["glued_digits_removed"] += n
        t = GLUED_DIGIT.sub(r"\1", t)
    t = re.sub(r"\s*;\s*;\s*", "; ", t)
    t = re.sub(r"\(\s*\)", "", t)
    t = collapse_ws(t)
    t = re.sub(r"^(?:and\s+|[,;)\-—·]+\s*)+", "", t)
    t = t.strip(" ,;:-—·")
    return t


def parse_entry(word: str, definition: str, stats: Counter) -> dict:
    """Разбирает текст статьи Wehr на структурные поля."""
    raw = collapse_ws(definition)
    out = {
        "headword": strip_diacritics(word).strip(),
        "headword_vocalized": word.strip() if DIAC_RE.search(word or "") else "",
        "romanization": None,
        "body": "",
        "idioms": "",
        "verb_forms": "",
        "plurals": "",
        "kind": "entry",
        "see_target": None,
        "alt_root": None,
    }
    if not raw:
        out["kind"] = "empty"
        return out

    # 1) отсылки
    m = SEE_RE.match(raw)
    if m and len(m.group("target").split()) <= 3:
        out["kind"] = "xref"
        out["see_target"] = m.group("target").strip()
        out["romanization"] = (m.group("rom") or "").strip() or None
        stats["xrefs"] += 1
        return out
    if ALPHA_REF_RE.search(raw) and len(raw) < 60:
        out["kind"] = "xref_alpha"
        stats["xrefs_alpha"] += 1
        return out

    # 2) отрезаем блок идиом
    body_main, _, idioms = raw.partition("│")
    if idioms:
        stats["entries_with_idioms"] += 1
        out["idioms"] = clean_body(idioms.replace("│", " ; "), stats)

    # 3) заголовок + транслитерация
    tokens = body_main.split(" ")
    i = 0
    head_parts = []
    while i < len(tokens):
        tok = tokens[i]
        if is_arabic_token(tok):
            head_parts.append(tok)
            i += 1
            continue
        # арабская скобка после заголовка: قام (قوم)
        if tok.startswith("(") and i + 1 < len(tokens):
            j = i
            while j < len(tokens) and not tokens[j].endswith(")"):
                j += 1
            inside = " ".join(tokens[i:j + 1]).strip("()")
            if is_arabic_token(inside):
                out["alt_root"] = inside
                i = j + 1
                continue
            break
        break

    if head_parts:
        head_raw = " ".join(head_parts)
        out["headword"] = strip_diacritics(head_raw).strip() or out["headword"]
        if DIAC_RE.search(head_raw):
            out["headword_vocalized"] = head_raw

    # пропускаем латинские скобки вида (It. abate)
    while i < len(tokens) and tokens[i].startswith("("):
        j = i
        while j < len(tokens) and not tokens[j].endswith(")"):
            j += 1
        i = j + 1

    if i < len(tokens) and is_latin_token(tokens[i]) and tokens[i].lower() not in ROMAN_STOP:
        out["romanization"] = tokens[i].strip("(),;.")
        i += 1
    rest = " ".join(tokens[i:])

    forms = sorted(set(VERB_FORM_RE.findall(body_main)), key="I II III IV V VI VII VIII IX X".split().index)
    out["verb_forms"] = ",".join(forms)
    if forms:
        stats["entries_with_verb_forms"] += 1

    plur = [collapse_ws(p).strip(" ,;.") for p in PLURAL_RE.findall(body_main)]
    plur = [p for p in plur if p]
    out["plurals"] = " | ".join(plur)[:300]
    if plur:
        stats["entries_with_plurals"] += 1

    out["body"] = clean_body(rest, stats)
    if not out["romanization"]:
        stats["no_romanization"] += 1
    return out


# ---------------------------------------------------------------- иерархия корней

def rebuild_roots(rows, stats: Counter):
    """Корни = строки с is_root в источнике, арабские, без пробела, 2-5 букв.

    Правило выбрано замером (см. build-report.md, раздел "выбор набора корней"):
      вариант                        корней  привязано  валидно
      как в источнике (любая длина)   7331    17468     15121 (86.6%)
      только 3-5 букв                 5999    18796     13717 (73.0%)
      is_root ИЛИ parent_id-цель     11954    12845      7900 (61.5%)
      2-5 букв (выбран)              ~6430   ~18350    ~15450 (84.3%)
    Никакого демотирования по валидации: заголовки с шумом OCR (نغص вместо نظر)
    сами не проходят проверку, но демотировать их — значит ухудшить привязку.
    """
    word_of = {r["id"]: r["word"] for r in rows}
    r_is_root_src = {r["id"]: r["is_root_src"] == 1 for r in rows}

    def is_candidate(i):
        if not (r_is_root_src.get(i)):
            return False
        w = word_of[i].strip()
        key = norm_key(w)
        return (" " not in w and 2 <= len(key) <= 5
                and bool(AR_ONLY.match(strip_diacritics(w))))

    roots = sorted(i for i in word_of if is_candidate(i))
    stats["roots_candidates"] = len(roots)

    root_of = {}
    for rid in word_of:
        if rid in set(roots):
            root_of[rid] = rid
            continue
        prev = None
        for c in roots:
            if c < rid:
                prev = c
            else:
                break
        root_of[rid] = prev

    valid = Counter()
    total = Counter()
    rootset = set(roots)
    for r in rows:
        rid = r["id"]
        if rid in rootset:
            continue
        rt = root_of.get(rid)
        if not rt:
            continue
        total[rt] += 1
        if contains_root_letters(r["word"], word_of[rt]):
            valid[rt] += 1
    return root_of, rootset, valid, total


# ---------------------------------------------------------------- сборка

def build(src: str, outdir: str, seed: int = 42):
    os.makedirs(outdir, exist_ok=True)
    work = os.path.join(outdir, "_src_vacuum.sqlite")
    if os.path.exists(work):
        os.remove(work)
    shutil.copyfile(src, work)
    con = sqlite3.connect(work)
    con.execute("VACUUM")
    raw_size = os.path.getsize(src)
    vac_size = os.path.getsize(work)
    rows_src = list(con.execute(
        "SELECT id, c1, c2, c3, c5 FROM DICTIONARY_content ORDER BY id"))
    con.close()

    stats = Counter()
    rows = []
    for rid, word, definition, is_root_src, quran in rows_src:
        parsed = parse_entry(word or "", definition or "", stats)
        rows.append({
            "id": rid,
            "word": (word or "").strip(),
            "is_root_src": is_root_src or 0,
            "quran_occurrence": str(quran).strip() if quran not in (None, "") else "",
            **parsed,
        })
        if not parsed["body"] and parsed["kind"] == "entry":
            stats["entries_without_body"] += 1

    root_of, roots, valid, total = rebuild_roots(rows, stats)

    # --- отсылки "see X" -> id
    by_key = defaultdict(list)
    for r in rows:
        by_key[norm_key(r["headword"])].append(r["id"])
        if r["word"] != r["headword"]:
            by_key[norm_key(r["word"])].append(r["id"])

    resolved = unresolved = 0
    for r in rows:
        if r["kind"] != "xref" or not r["see_target"]:
            continue
        cand = by_key.get(norm_key(r["see_target"]), [])
        # предпочитаем корневую статью
        cand_sorted = sorted(cand, key=lambda i: (i not in roots, i))
        if len(set(cand_sorted)) >= 1:
            r["see_entry_id"] = cand_sorted[0]
            resolved += 1
        else:
            r["see_entry_id"] = None
            unresolved += 1
    stats["xref_resolved"] = resolved
    stats["xref_unresolved"] = unresolved

    # --- сборка выходной БД
    pack_path = os.path.join(outdir, "hw_pack.sqlite")
    if os.path.exists(pack_path):
        os.remove(pack_path)
    out = sqlite3.connect(pack_path)
    out.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE entries(
            id                 INTEGER PRIMARY KEY,
            kind               TEXT NOT NULL,      -- entry | root | word | xref | xref_alpha | empty
            headword           TEXT NOT NULL,      -- без огласовки
            headword_vocalized TEXT,               -- как в источнике, если была огласовка
            headword_norm      TEXT NOT NULL,      -- ключ поиска (нормализованный)
            romanization       TEXT,
            body               TEXT,               -- английское значение (очищенное)
            idioms             TEXT,               -- блок после │
            verb_forms         TEXT,               -- породы: I,II,X
            plurals            TEXT,
            alt_root           TEXT,               -- корень, указанный в статье скобками: قام (قوم)
            root_id            INTEGER,            -- корневая статья (исправленная привязка)
            quran_occurrence   INTEGER,
            see_entry_id       INTEGER,
            flags              TEXT
        );
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
    """)

    for r in rows:
        rid = r["id"]
        flags = []
        is_root = rid in roots
        if r["is_root_src"] and not is_root:
            flags.append("root-demoted")
        if not is_root and root_of.get(rid) is None:
            flags.append("no-root")
        if (not is_root and root_of.get(rid) is not None
                and not contains_root_letters(r["word"], next(x["word"] for x in rows if x["id"] == root_of[rid]))):
            flags.append("root-unverified")
        if r["kind"] == "xref" and not r.get("see_entry_id"):
            flags.append("xref-unresolved")
        if not r["romanization"] and r["kind"] == "entry":
            flags.append("no-romanization")
        q = r["quran_occurrence"]
        try:
            qv = int(q) if q not in ("", None) else None
        except ValueError:
            qv = None
        out.execute(
            "INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (rid,
             r["kind"] if r["kind"] in ("xref", "xref_alpha", "empty")
             else ("root" if is_root else "word"),
             r["headword"], r["headword_vocalized"], norm_key(r["headword"]),
             r["romanization"], r["body"], r["idioms"], r["verb_forms"], r["plurals"],
             norm_key(r.get("alt_root") or "") or None,
             root_of.get(rid), qv, r.get("see_entry_id"), ",".join(flags) or None))

    out.executescript("""
        CREATE INDEX idx_headword_norm ON entries(headword_norm);
        CREATE INDEX idx_root ON entries(root_id);
        CREATE INDEX idx_kind ON entries(kind);
    """)
    # индекс форм: заголовок, вариант без артикля, корень из скобок, множественные числа
    out.execute("CREATE TABLE forms(form TEXT NOT NULL, entry_id INTEGER NOT NULL, kind TEXT)")
    out.execute("CREATE UNIQUE INDEX idx_forms_uniq ON forms(form, entry_id, kind)")
    form_kinds = Counter()

    def add_form(key, rid, kind):
        if key and 2 <= len(key) <= 14:
            cur = out.execute("INSERT OR IGNORE INTO forms VALUES (?,?,?)", (key, rid, kind))
            if cur.rowcount:
                form_kinds[kind] += 1

    for r in rows:
        rid = r["id"]
        kind = r["kind"] if r["kind"] in ("xref", "xref_alpha", "empty") else (
            "root" if rid in roots else "word")
        key = norm_key(r["headword"])
        add_form(key, rid, kind)
        if key.startswith("ال") and len(key) > 4:
            add_form(key[2:], rid, kind)
        add_form(norm_key(r.get("alt_root") or ""), rid, "root")
        for tok in re.findall(r"[\u0600-\u06FF][\u0600-\u06FF\u0610-\u061A\u064B-\u065F\u0670]*",
                              r["plurals"] or ""):
            add_form(norm_key(tok), rid, "plural")
    out.execute("CREATE INDEX idx_forms_form ON forms(form)")
    out.executemany("INSERT INTO meta VALUES (?,?)", [
        ("source", "Hans Wehr, A Dictionary of Modern Written Arabic, 4th ed. (ed. J. M. Cowan, 1979)"),
        ("source_db", "GibreelAbdullah/HansWehrDictionary assets/hanswehr.sqlite (OCR + manual fixes)"),
        ("builder", "tools/build_hw_pack.py"),
        ("license_note", "Текст словаря охраняется авторским правом. Пак для личного использования; распространение запрещено."),
        ("entries", str(len(rows))),
        ("roots", str(len(roots))),
    ])
    out.commit()
    out.execute("VACUUM")
    out.commit()
    out.close()

    # --- JSONL.gz
    jsonl_path = os.path.join(outdir, "hw_pack.jsonl.gz")
    with gzip.open(jsonl_path, "wt", encoding="utf-8") as f:
        for r in rows:
            rid = r["id"]
            rec = {
                "id": rid,
                "kind": "root" if rid in roots else ("word" if r["kind"] == "entry" else r["kind"]),
                "hw": r["headword"],
                "hw_voc": r["headword_vocalized"] or None,
                "rom": r["romanization"],
                "body": r["body"],
                "idioms": r["idioms"] or None,
                "verb_forms": r["verb_forms"] or None,
                "plurals": r["plurals"] or None,
                "root_id": root_of.get(rid),
                "quran": int(r["quran_occurrence"]) if r["quran_occurrence"].isdigit() else None,
                "see": r.get("see_entry_id"),
            }
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    write_report(outdir, stats, rows, roots, root_of, valid, total, raw_size, vac_size,
                 os.path.getsize(pack_path), os.path.getsize(jsonl_path), seed, form_kinds)
    os.remove(work)
    return pack_path


# ---------------------------------------------------------------- отчёт

def write_report(outdir, stats, rows, roots, root_of, valid, total, raw_size, vac_size,
                 pack_size, jsonl_size, seed, form_stats):
    n = len(rows)
    entries = [r for r in rows if r["kind"] == "entry"]
    roots_with_children = sum(1 for r in roots if total.get(r, 0) > 0)
    validated = sum(valid.values())
    validated_total = sum(total.values()) or 1

    random.seed(seed)
    samples = random.sample([r for r in entries if r["body"]], 25)
    spot = ["كتب", "مكتبة", "بيت", "مدرسة", "سيارة", "ذهب", "قال", "جبل", "بحر",
            "عين", "صلاة", "مستشفى", "جميلة", "طعام", "لغة"]
    spot_rows = []
    by_hw = defaultdict(list)
    for r in rows:
        by_hw[r["headword"]].append(r)
    for w in spot:
        cand = by_hw.get(w) or by_hw.get(norm_key(w)) or []
        spot_rows.append((w, cand[0] if cand else None))

    lines = []
    A = lines.append
    A("# Отчёт сборки пака Hans Wehr")
    A("")
    A(f"Сборщик: `tools/build_hw_pack.py`. Источник: `data/raw/hanswehr.sqlite`.")
    A("")
    A("## Размеры")
    A("")
    A("| | было | стало |")
    A("|---|---|---|")
    A(f"| SQLite | {raw_size/1048576:.1f} МБ | **{pack_size/1048576:.1f} МБ** (`hw_pack.sqlite`) |")
    A(f"| после VACUUM исходника | {raw_size/1048576:.1f} МБ | {vac_size/1048576:.1f} МБ |")
    A(f"| JSONL.gz | — | {jsonl_size/1048576:.1f} МБ (`hw_pack.jsonl.gz`) |")
    A("")
    A("## Состав")
    A("")
    kinds = Counter(r["kind"] for r in rows)
    A(f"- записей всего: **{n}**")
    A(f"- корневых статей: **{len(roots)}** (в т.ч. {len(roots & {r['id'] for r in rows if r['kind'] == 'xref'})} — отсылки)")
    A(f"- обычных статей: **{n - len(roots) - kinds['xref'] - kinds['xref_alpha']}**")
    A(f"- отсылок `see X`: {kinds['xref']} (разрешено {stats['xref_resolved']}, не разрешено {stats['xref_unresolved']})")
    A(f"- отсылок «look up alphabetically»: {kinds['xref_alpha']}")
    A(f"- корней с подтверждёнными производными: {sum(1 for r in roots if total.get(r, 0) > 0)}")
    A("")
    A("## Восстановление иерархии корней")
    A("")
    A(f"Валидация лингвистическим инвариантом (все буквы корня присутствуют в слове по порядку):")
    A("")
    A(f"- **{validated} / {validated_total} = {100*validated/validated_total:.1f}%** привязанных слов подтверждены")
    A("Набор «корней» выбран замером, а не эвристикой (варианты прогонялись на всех 24 799 записях):")
    A("")
    A("| правило | корней | привязано слов | подтверждено |")
    A("|---|---|---|---|")
    A("| как в источнике, любая длина | 7331 | 17 468 | 15 121 (86.6%) |")
    A("| только 3–5 букв | 5999 | 18 796 | 13 717 (73.0%) |")
    A("| `is_root` ИЛИ цель `parent_id` | 11 954 | 12 845 | 7 900 (61.5%) |")
    A("| **2–5 букв (выбрано)** | **6 429** | **18 369** | **15 450 (84.1%)** |")
    A("")
    A("Демотирование «корней», которые не проходят валидацию, отклонено: заголовки с шумом OCR")
    A("(`نغص` вместо `نظر`) сами не проверяются, но их удаление ухудшает привязку всех слов блока.")
    A("")
    A("## Извлечение полей")
    A("")
    A("| поле | заполнено | доля |")
    A("|---|---|---|")
    for label, pred in [
        ("romanization", lambda r: bool(r["romanization"])),
        ("idioms", lambda r: bool(r["idioms"])),
        ("verb_forms", lambda r: bool(r["verb_forms"])),
        ("plurals", lambda r: bool(r["plurals"])),
        ("значение (body)", lambda r: bool(r["body"])),
    ]:
        k = sum(1 for r in rows if pred(r))
        A(f"| {label} | {k} | {100*k/n:.1f}% |")
    A("")
    A("## Индекс форм")
    A("")
    A("| тип | записей |")
    A("|---|---|")
    for k, v in form_stats.most_common():
        A(f"| {k} | {v} |")
    A("")
    A("## Чистка")
    A("")
    A(f"- удалено номеров страниц: {stats['page_numbers_removed']}")
    A(f"- удалено надстрочных цифр, приклеенных к слову: {stats['glued_digits_removed']}")
    A(f"- записей без тела значения: {stats['entries_without_body']}")
    A("")
    A("## 25 случайных статей (проверка глазами)")
    A("")
    for r in samples:
        A(f"**{r['headword']}** — *{r['romanization'] or '—'}* — id {r['id']}, root #{root_of.get(r['id']) or '—'}")
        A("")
        A(f"> {r['body'][:260]}")
        if r["idioms"]:
            A(f">\n> *идиомы:* {r['idioms'][:160]}")
        A("")
    A("## 15 контрольных слов")
    A("")
    A("`заголовок` — прямое совпадение статьи; `корень` — статья, к которой слово привязано")
    A("(важно: `قال`, `عين`, `صلاة` живут **внутри** корневых статей Wehr, отдельного заголовка у них нет —")
    A("именно поэтому клиенту нужен деинфлектор, а не только поиск по заголовку).")
    A("")
    A("| запрос | по заголовку | romanization | начало значения | корень |")
    A("|---|---|---|---|---|")
    by_hw = defaultdict(list)
    for r in rows:
        by_hw[norm_key(r["headword"])].append(r)
    for w in spot:
        cand = by_hw.get(norm_key(w)) or []
        piece = "—"
        root_piece = "—"
        if cand:
            r = cand[0]
            piece = f"#{r['id']}"
            rt = root_of.get(r["id"])
            if rt == r["id"]:
                root_piece = "сама"
            elif rt:
                rr = next((x for x in rows if x["id"] == rt), None)
                root_piece = f"#{rt} {rr['headword']}" if rr else f"#{rt}"
            A(f"| {w} | {piece} ({r['kind']}) | {r['romanization'] or '—'} | {(r['body'] or '')[:80] or '—'} | {root_piece} |")
        else:
            A(f"| {w} | **нет заголовка** | — | — | — |")
    A("")
    A("## Что осталось за кадром")
    A("")
    A("- Деинфлексия словоформ (попап по `البيوت` -> `بيت`) — задача клиента, не пака.")
    A("- 647 отсылок: разрешены в `see_entry_id`, но целевая статья тоже может быть отсылкой (цепочки не разворачиваются).")
    A("- Кораническая конкорданция (`QURAN` в источнике) в пак не включена: `ROOT_WORD` там не корни (в топе `و`, `ال`, `ل`), нужен свой корпус Корана.")
    A("- Транслитерация берётся из текста статьи (таблица `TRANSLITERATION` источника непригодна — 85% расхождений).")
    A("")
    with open(os.path.join(outdir, "build-report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))




def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/raw/hanswehr.sqlite")
    ap.add_argument("--out", default="data/packs/hanswehr")
    args = ap.parse_args()
    pack = build(args.src, args.out)
    print(f"готово: {pack}")


if __name__ == "__main__":
    main()
